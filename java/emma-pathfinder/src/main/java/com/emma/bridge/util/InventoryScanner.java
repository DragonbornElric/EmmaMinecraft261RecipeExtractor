package com.emma.bridge.util;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Shared inventory scanning helpers — single source of truth for the
 * "for (int i = 0; i < 36; i++) { getItem(i)... }" pattern that was
 * previously duplicated across 14+ files.
 *
 * All methods scan slots 0-35 (hotbar 0-8 + main inventory 9-35).
 * Offhand (slot 40) and armor slots are NOT included — callers that
 * need those should check them separately.
 */
public final class InventoryScanner {

    private InventoryScanner() {}

    /** A slot index paired with its ItemStack. */
    public record SlotStack(int slot, ItemStack stack) {}

    /**
     * Count the total number of items (summing stack sizes) matching a predicate.
     */
    public static int countItems(Inventory inv, Predicate<ItemStack> predicate) {
        int total = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && predicate.test(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * Count the number of slots (not items) matching a predicate.
     */
    public static int countSlots(Inventory inv, Predicate<ItemStack> predicate) {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && predicate.test(stack)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Count empty inventory slots.
     */
    public static int emptySlots(Inventory inv) {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            if (inv.getItem(i).isEmpty()) count++;
        }
        return count;
    }

    /**
     * Find the first slot (0-35) containing an item matching the predicate, or -1.
     */
    public static int findSlot(Inventory inv, Predicate<ItemStack> predicate) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && predicate.test(stack)) return i;
        }
        return -1;
    }

    /**
     * Check if the player has a specific Item anywhere in slots 0-35.
     */
    public static boolean hasItem(Inventory inv, Item item) {
        return findSlot(inv, stack -> stack.is(item)) >= 0;
    }

    /**
     * Collect all (slot, stack) pairs matching a predicate.
     */
    public static List<SlotStack> findAll(Inventory inv, Predicate<ItemStack> predicate) {
        List<SlotStack> result = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && predicate.test(stack)) {
                result.add(new SlotStack(i, stack));
            }
        }
        return result;
    }
}
