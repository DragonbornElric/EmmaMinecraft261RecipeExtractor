package adris.altoclef.tasks.slot;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Withdraws needed items from the overflow virtual inventory.
 *
 * Three-phase async pattern (same as EnsureFreeInventorySlotTask):
 * <ol>
 *   <li>Phase 1 (tick 0): Check cached overflow status, build withdraw request, send C2S.</li>
 *   <li>Phase 2 (ticks 1-10): Poll future.isDone() — wait for S2C response.</li>
 *   <li>Phase 3 (3 ticks after response): Wait for inventory sync packets from
 *       server's playerScreenHandler.syncState() to propagate to client-side PlayerInventory.</li>
 * </ol>
 *
 * ONE attempt only — no retries to avoid loops.
 */
public class WithdrawFromOverflowTask extends Task {

    private static final int MAX_WAIT_TICKS = 10;
    private static final int SYNC_WAIT_TICKS = 3; // wait for inventory sync after response

    private final ItemTarget[] neededItems;
    private boolean requestSent = false;
    private CompletableFuture<JsonObject> withdrawFuture = null;
    private int waitTicks = 0;
    private int syncCountdown = -1; // -1 = not started
    private boolean done = false;

    /**
     * @param neededItems the item targets this task should try to withdraw from overflow.
     *                    Counts represent the full target — shortage is computed internally.
     */
    public WithdrawFromOverflowTask(ItemTarget[] neededItems) {
        this.neededItems = neededItems;
    }

    @Override
    public boolean isFinished() {
        return done;
    }

    @Override
    protected void onStart() {
        requestSent = false;
        withdrawFuture = null;
        waitTicks = 0;
        syncCountdown = -1;
        done = false;
    }

    @Override
    protected Task onTick() {
        // ── Phase 1: Send withdraw request (once) ──
        if (!requestSent) {
            requestSent = true;
            try {
                if (!com.emma.overflow.OverflowClientMod.OverflowClientApi.isAvailable()) {
                    done = true;
                    return null;
                }

                // Check cached status to build withdraw list (no round-trip)
                JsonObject cached = com.emma.overflow.OverflowClientMod.OverflowClientApi.getCachedStatus();
                if (cached == null || !cached.has("items")) {
                    done = true;
                    return null;
                }

                // Build map of what overflow actually has
                Map<String, Integer> overflowContents = new HashMap<>();
                for (JsonElement elem : cached.getAsJsonArray("items")) {
                    JsonObject obj = elem.getAsJsonObject();
                    overflowContents.put(
                            obj.get("item").getAsString(),
                            obj.get("count").getAsInt()
                    );
                }

                // For each needed item, request min(shortage, overflow available)
                JsonObject req = new JsonObject();
                req.addProperty("action", "withdraw");
                JsonArray itemsArray = new JsonArray();
                boolean anyToWithdraw = false;

                AltoClef mod = AltoClef.getInstance();
                for (ItemTarget target : neededItems) {
                    int inventoryCount = mod.getItemStorage().getItemCountInventoryOnly(target.getMatches());
                    int shortage = target.getTargetCount() - inventoryCount;
                    if (shortage <= 0) continue;

                    for (Item match : target.getMatches()) {
                        String itemId = Registries.ITEM.getId(match).toString();
                        int available = overflowContents.getOrDefault(itemId, 0);
                        if (available > 0) {
                            int toWithdraw = Math.min(shortage, available);
                            JsonObject entry = new JsonObject();
                            entry.addProperty("item", itemId);
                            entry.addProperty("count", toWithdraw);
                            itemsArray.add(entry);
                            shortage -= toWithdraw;
                            anyToWithdraw = true;
                        }
                        if (shortage <= 0) break;
                    }
                }

                if (!anyToWithdraw) {
                    done = true;
                    return null;
                }

                req.add("items", itemsArray);
                withdrawFuture = com.emma.overflow.OverflowClientMod.OverflowClientApi.sendRequest(req);
                waitTicks = 0;
                setDebugState("Overflow: withdrawing items");
                return null;

            } catch (NoClassDefFoundError ignored) {
                done = true;
                return null;
            }
        }

        // ── Phase 2: Wait for S2C response ──
        if (withdrawFuture != null && !withdrawFuture.isDone()) {
            waitTicks++;
            if (waitTicks >= MAX_WAIT_TICKS) {
                Debug.logMessage("WithdrawFromOverflowTask: timed out after " + waitTicks + " ticks");
                done = true;
                return null;
            }
            setDebugState("Overflow: waiting for withdraw (" + waitTicks + " ticks)");
            return null;
        }

        // ── Phase 3: Response arrived — start sync countdown ──
        if (syncCountdown < 0 && withdrawFuture != null && withdrawFuture.isDone()) {
            try {
                JsonObject result = withdrawFuture.getNow(null);
                if (result != null && result.has("withdrawn")) {
                    Debug.logMessage("WithdrawFromOverflowTask: withdrew " + result.get("withdrawn"));
                }
            } catch (Exception e) {
                Debug.logMessage("WithdrawFromOverflowTask: error: " + e.getMessage());
            }
            syncCountdown = SYNC_WAIT_TICKS;
        }

        if (syncCountdown > 0) {
            syncCountdown--;
            setDebugState("Overflow: waiting for inventory sync (" + syncCountdown + ")");
            return null;
        }

        // syncCountdown == 0 or withdrawFuture was null — we're done
        done = true;
        return null;
    }

    @Override
    protected void onStop(Task interruptTask) {
        // Don't cancel the future — let it complete naturally
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof WithdrawFromOverflowTask;
    }

    @Override
    protected String toDebugString() {
        return "Withdrawing from overflow";
    }
}
