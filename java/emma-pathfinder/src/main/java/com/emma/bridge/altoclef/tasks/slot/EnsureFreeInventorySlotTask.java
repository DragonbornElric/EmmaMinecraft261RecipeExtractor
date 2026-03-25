package adris.altoclef.tasks.slot;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.Slot;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class EnsureFreeInventorySlotTask extends Task {

    // When all items are protected for this many ticks, force-drop via last resort
    private static final int PROTECTED_DEADLOCK_TICKS = 30; // ~1.5 seconds

    /** Max ticks to wait for overflow server response before falling through. */
    private static final int MAX_OVERFLOW_WAIT_TICKS = 10; // ~0.5 seconds

    private int allProtectedTicks = 0;
    private boolean overflowRequested = false;
    private CompletableFuture<JsonObject> overflowFuture = null;
    private int overflowWaitTicks = 0;

    @Override
    protected void onStart() {
        allProtectedTicks = 0;
        overflowRequested = false;
        overflowFuture = null;
        overflowWaitTicks = 0;
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();

        // ── Phase 0: Send overflow request (once) ──
        if (!overflowRequested) {
            try {
                if (com.emma.overflow.OverflowClientMod.OverflowClientApi.isAvailable()) {
                    JsonObject req = new JsonObject();
                    req.addProperty("action", "free_slots");
                    req.addProperty("target", 1);
                    JsonArray junkList = new JsonArray();
                    for (net.minecraft.item.Item item : mod.getModSettings().getThrowawayItems(true)) {
                        junkList.add(net.minecraft.registry.Registries.ITEM.getId(item).toString());
                    }
                    req.add("junk_items", junkList);

                    // Tell server how much food to keep in inventory
                    int foodThreshold = mod.getModSettings().getFoodUnitsToCollect();
                    if (foodThreshold > 0) {
                        req.addProperty("food_threshold", foodThreshold);
                    }

                    // Don't deposit items the current task needs
                    java.util.Set<net.minecraft.item.Item> protectedItems = mod.getBehaviour().getProtectedItems();
                    if (!protectedItems.isEmpty()) {
                        JsonArray protectedList = new JsonArray();
                        for (net.minecraft.item.Item item : protectedItems) {
                            protectedList.add(net.minecraft.registry.Registries.ITEM.getId(item).toString());
                        }
                        req.add("protected_items", protectedList);
                    }

                    overflowFuture = com.emma.overflow.OverflowClientMod.OverflowClientApi.sendRequest(req);
                    overflowRequested = true;
                    overflowWaitTicks = 0;
                    setDebugState("Overflow: requested server to free slot");
                    return null;
                }
            } catch (NoClassDefFoundError ignored) {
                // Overflow mod not installed — fall through to Phase 1
            }
        }

        // ── Phase 0b: Wait for overflow response ──
        if (overflowFuture != null) {
            if (!overflowFuture.isDone()) {
                overflowWaitTicks++;
                if (overflowWaitTicks < MAX_OVERFLOW_WAIT_TICKS) {
                    setDebugState("Overflow: waiting for server (" + overflowWaitTicks + " ticks)");
                    return null; // Keep waiting — don't fall through to drop logic
                }
                // Timed out waiting — cancel and fall through
                Debug.logMessage("EnsureFreeInventorySlotTask: overflow wait timed out after "
                        + overflowWaitTicks + " ticks");
                overflowFuture = null;
            } else {
                // Response arrived — check if server freed slots
                try {
                    JsonObject result = overflowFuture.getNow(null);
                    if (result != null && result.has("slots_freed")) {
                        int freed = result.get("slots_freed").getAsInt();
                        if (freed > 0) {
                            Debug.logMessage("EnsureFreeInventorySlotTask: overflow freed "
                                    + freed + " slot(s)");
                            overflowFuture = null;
                            // Server freed slots — inventory sync will happen within
                            // 1-2 ticks. Return null so the parent task re-evaluates.
                            return null;
                        }
                    }
                } catch (Exception e) {
                    Debug.logMessage("EnsureFreeInventorySlotTask: overflow error: " + e.getMessage());
                }
                overflowFuture = null;
                // Overflow didn't free slots — fall through to Phase 1
            }
        }

        // ── Phase 1: Normal garbage slot handling ──
        ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();
        Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
        if (cursorStack.isEmpty()) {
            if (garbage.isPresent()) {
                allProtectedTicks = 0;
                mod.getSlotHandler().clickSlot(garbage.get(), 0, SlotActionType.PICKUP);
                return null;
            }
        }
        if (!cursorStack.isEmpty()) {
            allProtectedTicks = 0;
            LookHelper.randomOrientation();
            mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
            return null;
        }

        // All items are protected — track how long we've been stuck
        allProtectedTicks++;
        if (allProtectedTicks >= PROTECTED_DEADLOCK_TICKS) {
            // Last resort: find the least important item and force-drop it.
            // Better to lose one item than deadlock the entire task system.
            Optional<Slot> lastResort = StorageHelper.getLastResortGarbageSlot(mod);
            if (lastResort.isPresent()) {
                Debug.logMessage("EnsureFreeInventorySlotTask: all items protected for "
                        + allProtectedTicks + " ticks — force-dropping from " + lastResort.get());
                mod.getSlotHandler().clickSlot(lastResort.get(), 0, SlotActionType.PICKUP);
                allProtectedTicks = 0;
                return null;
            }
        }

        setDebugState("All items are protected.");
        return null;
    }

    @Override
    protected void onStop(Task interruptTask) {

    }

    @Override
    protected boolean isEqual(Task obj) {
        return obj instanceof EnsureFreeInventorySlotTask;
    }

    @Override
    protected String toDebugString() {
        return "Ensuring inventory is free";
    }
}
