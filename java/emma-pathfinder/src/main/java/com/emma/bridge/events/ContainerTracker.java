package com.emma.bridge.events;

import com.emma.bridge.BridgeServer;
import com.emma.bridge.EmmaBridgeMod;
import com.emma.bridge.control.BlockInteraction;
import com.emma.bridge.websocket.JsonProtocol;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tick-based container content cache.
 *
 * Detects when the player opens a container screen, reads its slots,
 * and caches the contents keyed by BlockPos. On screen close, broadcasts
 * a {@code container_contents} event via WebSocket so the Python side
 * can persist to SQLite.
 *
 * Follows the same tick-poll pattern as {@link InventoryTracker}.
 */
public class ContainerTracker {

    /** Maximum cached containers before evicting oldest entries. */
    private static final int MAX_CACHE_SIZE = 256;

    private final BridgeServer ws;

    /** In-memory cache: BlockPos → snapshot of container contents. */
    private final LinkedHashMap<Long, ContainerSnapshot> cache =
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, ContainerSnapshot> eldest) {
                    return size() > MAX_CACHE_SIZE;
                }
            };

    /** Whether a container screen was open on the previous tick. */
    private boolean wasContainerOpen = false;

    /** The BlockPos associated with the currently open container. */
    private BlockPos currentContainerPos = null;

    /** Hash of the container's contents last tick (for change detection). */
    private String lastContainerHash = "";

    public ContainerTracker(BridgeServer ws) {
        this.ws = ws;
    }

    // ── Tick ──────────────────────────────────────────────────────

    public void tick(LocalPlayer player) {
        if (player == null) return;

        AbstractContainerMenu menu = player.containerMenu;
        boolean isContainerOpen = isContainerScreen(menu);

        if (isContainerOpen && !wasContainerOpen) {
            // Container just opened — associate with last interacted block
            currentContainerPos = BlockInteraction.lastInteractedBlockPos;
            lastContainerHash = "";
            if (currentContainerPos != null) {
                EmmaBridgeMod.LOGGER.debug("[ContainerTracker] Container opened at {}",
                        currentContainerPos);
            }
        }

        if (isContainerOpen && currentContainerPos != null) {
            // Container is open — snapshot contents on change
            String hash = computeContainerHash(menu);
            if (!hash.equals(lastContainerHash)) {
                lastContainerHash = hash;
                ContainerSnapshot snapshot = captureSnapshot(menu, currentContainerPos);
                cache.put(posKey(currentContainerPos), snapshot);
            }
        }

        if (!isContainerOpen && wasContainerOpen && currentContainerPos != null) {
            // Container just closed — broadcast event with final snapshot
            ContainerSnapshot snapshot = getSnapshot(currentContainerPos);
            if (snapshot != null) {
                broadcastContainerContents(snapshot, currentContainerPos);
            }
            currentContainerPos = null;
            lastContainerHash = "";
        }

        wasContainerOpen = isContainerOpen;
    }

    // ── Public API ───────────────────────────────────────────────

    /** Get cached snapshot for a block position, or null. */
    public ContainerSnapshot getSnapshot(BlockPos pos) {
        return cache.get(posKey(pos));
    }

    /** Check if we have cached data for a position. */
    public boolean hasData(BlockPos pos) {
        return cache.containsKey(posKey(pos));
    }

    /**
     * Aggregate item counts across ALL cached containers.
     * Used by WorldState.setKnownStorage().
     */
    public Map<String, Integer> getAggregatedItemCounts() {
        Map<String, Integer> totals = new HashMap<>();
        for (ContainerSnapshot snapshot : cache.values()) {
            for (var entry : snapshot.items.entrySet()) {
                totals.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
        }
        return totals;
    }

    /** Get all cached snapshots (for scan enrichment). */
    public Map<Long, ContainerSnapshot> getAllSnapshots() {
        return Map.copyOf(cache);
    }

    /** Invalidate cache for a specific position (e.g. container destroyed). */
    public void invalidate(BlockPos pos) {
        cache.remove(posKey(pos));
    }

    /** Clear all cached data. */
    public void clear() {
        cache.clear();
    }

    // ── Snapshot capture ─────────────────────────────────────────

    private ContainerSnapshot captureSnapshot(AbstractContainerMenu menu, BlockPos pos) {
        int totalSlots = menu.slots.size() - 36; // subtract player inventory
        if (totalSlots <= 0) totalSlots = menu.slots.size();

        String type = identifyContainerType(menu, totalSlots);
        Map<String, Integer> items = new HashMap<>();
        JsonArray slotDetails = new JsonArray();
        int emptySlots = 0;

        for (int i = 0; i < totalSlots; i++) {
            ItemStack stack = menu.slots.get(i).getItem();
            if (stack.isEmpty()) {
                emptySlots++;
            } else {
                String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                items.merge(itemId, stack.getCount(), Integer::sum);

                JsonObject slotObj = new JsonObject();
                slotObj.addProperty("slot", i);
                slotObj.addProperty("item", itemId);
                slotObj.addProperty("count", stack.getCount());
                slotDetails.add(slotObj);
            }
        }

        return new ContainerSnapshot(type, items, slotDetails, totalSlots, emptySlots,
                pos.getX(), pos.getY(), pos.getZ());
    }

    private String identifyContainerType(AbstractContainerMenu menu, int containerSlots) {
        if (menu instanceof ChestMenu) {
            return containerSlots > 27 ? "double_chest" : "chest";
        }
        if (menu instanceof HopperMenu) return "hopper";
        if (menu instanceof DispenserMenu) return "dispenser";
        return "container";
    }

    // ── Event broadcast ──────────────────────────────────────────

    private void broadcastContainerContents(ContainerSnapshot snapshot, BlockPos pos) {
        JsonObject data = new JsonObject();

        JsonArray posArr = new JsonArray();
        posArr.add(pos.getX());
        posArr.add(pos.getY());
        posArr.add(pos.getZ());
        data.add("pos", posArr);

        data.addProperty("type", snapshot.type);
        data.add("items", snapshot.slotDetails);
        data.addProperty("total_slots", snapshot.totalSlots);
        data.addProperty("empty_slots", snapshot.emptySlots);

        ws.broadcastEvent(JsonProtocol.event("container_contents", data));
        EmmaBridgeMod.LOGGER.info("[ContainerTracker] Broadcast contents for {} at [{},{},{}]: {} item types, {}/{} slots used",
                snapshot.type, pos.getX(), pos.getY(), pos.getZ(),
                snapshot.items.size(), snapshot.totalSlots - snapshot.emptySlots, snapshot.totalSlots);
    }

    // ── Helpers ──────────────────────────────────────────────────

    /** Check if the menu is a container (not player inventory or crafting). */
    private static boolean isContainerScreen(AbstractContainerMenu menu) {
        if (menu instanceof InventoryMenu) return false; // player inventory
        // Container types we track
        return menu instanceof ChestMenu
                || menu instanceof HopperMenu
                || menu instanceof DispenserMenu;
    }

    /** Compute a hash of container slot contents for change detection. */
    private static String computeContainerHash(AbstractContainerMenu menu) {
        int containerSlots = menu.slots.size() - 36;
        if (containerSlots <= 0) containerSlots = menu.slots.size();

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < containerSlots; i++) {
            ItemStack stack = menu.slots.get(i).getItem();
            if (!stack.isEmpty()) {
                sb.append(i).append(":")
                        .append(BuiltInRegistries.ITEM.getKey(stack.getItem()))
                        .append("x").append(stack.getCount()).append(";");
            }
        }
        return sb.toString();
    }

    /** Pack BlockPos into a long key for the cache map. */
    private static long posKey(BlockPos pos) {
        return pos.asLong();
    }

    // ── Snapshot data class ──────────────────────────────────────

    /**
     * Immutable snapshot of a container's contents at a point in time.
     */
    public static class ContainerSnapshot {
        public final String type;
        public final Map<String, Integer> items;  // aggregated {itemId: count}
        public final JsonArray slotDetails;        // per-slot [{slot, item, count}]
        public final int totalSlots;
        public final int emptySlots;
        public final int x, y, z;

        public ContainerSnapshot(String type, Map<String, Integer> items, JsonArray slotDetails,
                                 int totalSlots, int emptySlots, int x, int y, int z) {
            this.type = type;
            this.items = Map.copyOf(items);
            this.slotDetails = slotDetails;
            this.totalSlots = totalSlots;
            this.emptySlots = emptySlots;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
