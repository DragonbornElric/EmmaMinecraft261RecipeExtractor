package com.emma.bridge.util;

import com.emma.bridge.EmmaBridgeMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * Bridge between GOAP and the Endless Inventory mod (emma-endinv).
 * All access is guarded by try/catch NoClassDefFoundError so the bridge mod
 * works fine when endinv is not installed.
 */
public final class EndinvBridge {

    private EndinvBridge() {}

    private static Boolean available;

    /** Check if endinv mod is loaded. Result is cached after first call. */
    public static boolean isAvailable() {
        if (available != null) return available;
        try {
            available = com.emma.endinv.client.CachedSrcInv.INSTANCE != null;
        } catch (NoClassDefFoundError e) {
            available = false;
        }
        return available;
    }

    /**
     * Get all item counts from the endless inventory (client-side cache).
     * Returns {itemId -> count} map, e.g. {"minecraft:stone" -> 2112}.
     */
    public static Map<String, Integer> getAllItems() {
        Map<String, Integer> result = new HashMap<>();
        if (!isAvailable()) {
            EmmaBridgeMod.LOGGER.debug("[EndinvBridge] Not available");
            return result;
        }
        try {
            var cache = com.emma.endinv.client.CachedSrcInv.INSTANCE;
            if (cache == null) {
                EmmaBridgeMod.LOGGER.debug("[EndinvBridge] CachedSrcInv.INSTANCE is null");
                return result;
            }
            var itemMap = cache.getItemMap();
            if (itemMap == null || itemMap.isEmpty()) {
                EmmaBridgeMod.LOGGER.debug("[EndinvBridge] Item map is empty");
                return result;
            }
            for (var entry : itemMap.entrySet()) {
                String id = BuiltInRegistries.ITEM.getKey(entry.getKey().item()).toString();
                result.merge(id, entry.getValue().count(), Integer::sum);
            }
        } catch (NoClassDefFoundError | Exception e) {
            EmmaBridgeMod.LOGGER.debug("[EndinvBridge] Failed to read endinv cache", e);
        }
        return result;
    }

    /**
     * Get count of a specific item in endinv.
     * @param itemId full item ID, e.g. "minecraft:iron_ingot"
     */
    public static int getCount(String itemId) {
        if (!isAvailable()) return 0;
        try {
            var cache = com.emma.endinv.client.CachedSrcInv.INSTANCE;
            for (var entry : cache.getItemMap().entrySet()) {
                String id = BuiltInRegistries.ITEM.getKey(entry.getKey().item()).toString();
                if (id.equals(itemId)) {
                    return entry.getValue().count();
                }
            }
        } catch (NoClassDefFoundError | Exception e) {
            // endinv not loaded
        }
        return 0;
    }

    /**
     * Extract items from endless inventory to a specific hotbar slot.
     * Sends an ItemClickPayload(SWAP) packet to the server.
     *
     * @param itemId full item ID, e.g. "minecraft:cooked_beef"
     * @param hotbarSlot hotbar slot index (0-8)
     * @return true if the extraction packet was sent (item exists in endinv)
     */
    public static boolean extractToSlot(String itemId, int hotbarSlot) {
        if (!isAvailable()) return false;
        try {
            var cache = com.emma.endinv.client.CachedSrcInv.INSTANCE;
            for (var entry : cache.getItemMap().entrySet()) {
                String id = BuiltInRegistries.ITEM.getKey(entry.getKey().item()).toString();
                if (id.equals(itemId) && entry.getValue().count() > 0) {
                    var payload = new com.emma.endinv.network.payloads.toServer.ItemClickPayload(
                            entry.getKey(), hotbarSlot,
                            net.minecraft.world.inventory.ContainerInput.SWAP
                    );
                    com.emma.endinv.network.FabricClientNetworking.sendToServer(payload);
                    // Update client cache optimistically
                    cache.takeItem(entry.getKey().toStack(entry.getValue().count()),
                            Math.min(entry.getValue().count(),
                                    entry.getKey().toStack(1).getMaxStackSize()));
                    EmmaBridgeMod.LOGGER.info("[EndinvBridge] Extracting {} to hotbar slot {}",
                            itemId, hotbarSlot);
                    return true;
                }
            }
        } catch (NoClassDefFoundError | Exception e) {
            EmmaBridgeMod.LOGGER.warn("[EndinvBridge] Extract failed", e);
        }
        return false;
    }

    /**
     * Extract items from endless inventory to a hotbar slot, matching by Item instance.
     * Used by forceEquipItem which has the Item object directly.
     */
    public static boolean extractToSlot(Item item, int hotbarSlot) {
        return extractToSlot(BuiltInRegistries.ITEM.getKey(item).toString(), hotbarSlot);
    }

    /**
     * Quick-move an item from endless inventory into the currently open container.
     * Uses QUICK_MOVE which routes through the container's slot.mayPlace() rules.
     * For furnaces: fuel goes to fuel slot (1), smeltable items go to input slot (0).
     *
     * @param itemId full item ID, e.g. "minecraft:coal"
     * @return true if the extraction packet was sent
     */
    public static boolean quickMoveToContainer(String itemId) {
        if (!isAvailable()) return false;
        try {
            var cache = com.emma.endinv.client.CachedSrcInv.INSTANCE;
            for (var entry : cache.getItemMap().entrySet()) {
                String id = BuiltInRegistries.ITEM.getKey(entry.getKey().item()).toString();
                if (id.equals(itemId) && entry.getValue().count() > 0) {
                    var payload = new com.emma.endinv.network.payloads.toServer.ItemClickPayload(
                            entry.getKey(), 0,
                            net.minecraft.world.inventory.ContainerInput.QUICK_MOVE
                    );
                    com.emma.endinv.network.FabricClientNetworking.sendToServer(payload);
                    // Update client cache optimistically
                    cache.takeItem(entry.getKey().toStack(entry.getValue().count()),
                            Math.min(entry.getValue().count(),
                                    entry.getKey().toStack(1).getMaxStackSize()));
                    EmmaBridgeMod.LOGGER.info("[EndinvBridge] Quick-moving {} to open container",
                            itemId);
                    return true;
                }
            }
        } catch (NoClassDefFoundError | Exception e) {
            EmmaBridgeMod.LOGGER.warn("[EndinvBridge] quickMoveToContainer failed", e);
        }
        return false;
    }
}
