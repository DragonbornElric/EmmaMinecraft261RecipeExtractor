package com.emma.bridge.commands;

import com.emma.bridge.EmmaBridgeMod;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * Handles "inventory" command — returns the player's current inventory.
 *
 * Params: {} (none)
 * Returns: { "slots": [{"slot": int, "item": string, "count": int}, ...] }
 */
public class InventoryHandler implements ICommandHandler {

    @Override
    public String getCommand() {
        return "inventory";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            throw new RuntimeException("No player entity");
        }

        Inventory inventory = player.getInventory();
        JsonArray slots = new JsonArray();

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                JsonObject slot = new JsonObject();
                slot.addProperty("slot", i);
                slot.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                slot.addProperty("count", stack.getCount());
                if (stack.getDamageValue() > 0) {
                    slot.addProperty("damage", stack.getDamageValue());
                    slot.addProperty("max_damage", stack.getMaxDamage());
                }
                slots.add(slot);
            }
        }

        // Also include armor and offhand info
        JsonObject result = new JsonObject();
        result.add("slots", slots);
        result.addProperty("selected_slot", getSelectedSlot(inventory));
        return result;
    }

    private int getSelectedSlot(Inventory inv) {
        try {
            java.lang.reflect.Field f = Inventory.class.getDeclaredField("selectedSlot");
            f.setAccessible(true);
            return f.getInt(inv);
        } catch (Exception e) {
            return 0;
        }
    }
}
