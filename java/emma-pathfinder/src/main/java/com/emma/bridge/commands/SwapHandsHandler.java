package com.emma.bridge.commands;

import com.emma.bridge.EmmaBridgeMod;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.ContainerInput;

/**
 * Handles "swap_hands" command — swaps main hand and off hand items.
 * Uses the player screen handler's swap mechanic (F key equivalent).
 *
 * Params: {} (none)
 * Returns: { "main_hand": string, "off_hand": string }
 */
public class SwapHandsHandler implements ICommandHandler {

    @Override
    public String getCommand() {
        return "swap_hands";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        LocalPlayer player = HandlerUtils.requirePlayer();
        JsonObject result = new JsonObject();

        if (player == null) {
            result.addProperty("swapped", false);
            result.addProperty("reason", "no_player");
            return result;
        }
        Minecraft client = Minecraft.getInstance();

        // Slot 40 is the offhand slot in player inventory screen handler
        int syncId = player.containerMenu.containerId;
        int selectedSlot = player.getInventory().getSelectedSlot();

        // SWAP action with button = offhand slot (40) swaps selected hotbar with offhand
        client.gameMode.handleContainerInput(syncId, selectedSlot, 40, ContainerInput.SWAP, player);

        // Read the new state
        ItemStack mainInteractionHand = player.getInventory().getItem(player.getInventory().getSelectedSlot());
        ItemStack offInteractionHand = player.getOffhandItem();

        String mainItem = mainInteractionHand.isEmpty() ? "empty" : BuiltInRegistries.ITEM.getKey(mainInteractionHand.getItem()).toString();
        String offItem = offInteractionHand.isEmpty() ? "empty" : BuiltInRegistries.ITEM.getKey(offInteractionHand.getItem()).toString();

        result.addProperty("swapped", true);
        result.addProperty("main_hand", mainItem);
        result.addProperty("off_hand", offItem);

        EmmaBridgeMod.LOGGER.info("[Emma Bridge] swap_hands: main={} off={}", mainItem, offItem);
        return result;
    }
}
