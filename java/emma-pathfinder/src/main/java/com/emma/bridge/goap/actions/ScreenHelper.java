package com.emma.bridge.goap.actions;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;

/**
 * Shared screen/container utilities for GOAP actions.
 */
public final class ScreenHelper {

    private ScreenHelper() {}

    /**
     * Close the current container screen if one is open (but not the player's own inventory).
     */
    public static void closeIfOpen(Minecraft client) {
        if (client.player != null && client.player.containerMenu != null
                && !(client.player.containerMenu instanceof InventoryMenu)) {
            client.player.closeContainer();
        }
    }

    /**
     * Find a slot containing the given item in a container menu, starting from startSlot.
     * Uses exact item ID matching.
     *
     * @return slot index, or -1 if not found
     */
    public static int findItem(AbstractContainerMenu handler, int startSlot, String itemId) {
        for (int i = startSlot; i < handler.slots.size(); i++) {
            var stack = handler.slots.get(i).getItem();
            if (!stack.isEmpty()) {
                String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                if (id.equals(itemId)) return i;
            }
        }
        return -1;
    }

    /**
     * Find a slot containing the given item OR a tag-equivalent item in a container menu.
     * Falls back to tag group matching if exact match not found.
     *
     * @return slot index, or -1 if not found
     */
    public static int findItemOrTagEquivalent(AbstractContainerMenu handler, int startSlot, String itemId) {
        // Exact match first
        int exact = findItem(handler, startSlot, itemId);
        if (exact != -1) return exact;

        // Tag equivalent fallback
        String tagGroup = TagGroups.getItemTagGroup(itemId);
        if (tagGroup != null) {
            for (int i = startSlot; i < handler.slots.size(); i++) {
                var stack = handler.slots.get(i).getItem();
                if (!stack.isEmpty()) {
                    String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                    if (tagGroup.equals(TagGroups.getItemTagGroup(id))) return i;
                }
            }
        }
        return -1;
    }
}
