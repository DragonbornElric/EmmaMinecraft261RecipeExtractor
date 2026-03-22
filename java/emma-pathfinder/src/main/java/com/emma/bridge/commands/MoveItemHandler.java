package com.emma.bridge.commands;

import com.emma.bridge.EmmaBridgeMod;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ContainerInput;

/**
 * Handles "move_item" command — moves items between inventory slots.
 * Uses the client interaction manager's clickSlot to manipulate inventory.
 *
 * Params: { "from_slot": int, "to_slot": int, "count": int (optional, default all) }
 * Returns: { "moved": bool, "item": string, "from_slot": int, "to_slot": int }
 */
public class MoveItemHandler implements ICommandHandler {

    @Override
    public String getCommand() {
        return "move_item";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        LocalPlayer player = HandlerUtils.requirePlayer();
        JsonObject result = new JsonObject();

        if (player == null) {
            result.addProperty("moved", false);
            result.addProperty("reason", "no_player");
            return result;
        }
        Minecraft client = Minecraft.getInstance();

        if (!params.has("from_slot") || !params.has("to_slot")) {
            result.addProperty("moved", false);
            result.addProperty("reason", "missing_from_slot_or_to_slot");
            return result;
        }

        int fromSlot = params.get("from_slot").getAsInt();
        int toSlot = params.get("to_slot").getAsInt();

        ItemStack sourceStack = player.getInventory().getItem(fromSlot);
        if (sourceStack.isEmpty()) {
            result.addProperty("moved", false);
            result.addProperty("reason", "source_slot_empty");
            return result;
        }

        String itemName = BuiltInRegistries.ITEM.getKey(sourceStack.getItem()).toString();
        int syncId = player.containerMenu.containerId;

        // Pick up from source slot
        client.gameMode.handleContainerInput(syncId, fromSlot, 0, ContainerInput.PICKUP, player);
        // Place in destination slot
        client.gameMode.handleContainerInput(syncId, toSlot, 0, ContainerInput.PICKUP, player);

        result.addProperty("moved", true);
        result.addProperty("item", itemName);
        result.addProperty("from_slot", fromSlot);
        result.addProperty("to_slot", toSlot);

        EmmaBridgeMod.LOGGER.info("[Emma Bridge] move_item: {} from slot {} to slot {}",
                itemName, fromSlot, toSlot);
        return result;
    }
}
