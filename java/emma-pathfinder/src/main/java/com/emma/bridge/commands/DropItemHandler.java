package com.emma.bridge.commands;

import com.emma.bridge.EmmaBridgeMod;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ContainerInput;

/**
 * Handles "drop_item" command — drops items from inventory.
 * Can drop the currently held item or from a specific slot.
 *
 * Params: { "slot": int (optional, default = selected slot),
 *           "all": bool (optional, default false — drop single item vs full stack) }
 * Returns: { "dropped": bool, "item": string, "count": int }
 */
public class DropItemHandler implements ICommandHandler {

    @Override
    public String getCommand() {
        return "drop_item";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        LocalPlayer player = HandlerUtils.requirePlayer();
        JsonObject result = new JsonObject();

        if (player == null) {
            result.addProperty("dropped", false);
            result.addProperty("reason", "no_player");
            return result;
        }

        boolean dropAll = params.has("all") && params.get("all").getAsBoolean();

        if (params.has("slot")) {
            // Drop from specific slot via screen handler
            int slot = params.get("slot").getAsInt();
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                result.addProperty("dropped", false);
                result.addProperty("reason", "slot_empty");
                return result;
            }

            String itemName = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            int count = dropAll ? stack.getCount() : 1;
            int syncId = player.containerMenu.containerId;
            // button 1 = drop full stack (Q + Ctrl), button 0 = drop single
            int button = dropAll ? 1 : 0;
            // Map inventory index to screen slot (hotbar 0-8 -> screen 36-44)
            int screenSlot = (slot < 9) ? slot + 36 : slot;
            Minecraft.getInstance().gameMode.handleContainerInput(syncId, screenSlot, button, ContainerInput.THROW, player);

            result.addProperty("dropped", true);
            result.addProperty("item", itemName);
            result.addProperty("count", count);
        } else {
            // Drop currently selected item
            ItemStack held = player.getInventory().getItem(player.getInventory().getSelectedSlot());
            if (held.isEmpty()) {
                result.addProperty("dropped", false);
                result.addProperty("reason", "hand_empty");
                return result;
            }

            String itemName = BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
            int count = dropAll ? held.getCount() : 1;
            player.drop(dropAll);

            result.addProperty("dropped", true);
            result.addProperty("item", itemName);
            result.addProperty("count", count);
        }

        EmmaBridgeMod.LOGGER.info("[Emma Bridge] drop_item: {} (all={})",
                result.get("item"), dropAll);
        return result;
    }
}
