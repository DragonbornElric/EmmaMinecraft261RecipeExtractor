package com.emma.bridge.commands;

import com.emma.bridge.EmmaBridgeMod;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.ContainerInput;

/**
 * Handles "click_slot" command — clicks a slot in the currently open screen.
 * Works with any screen handler: crafting, trading, brewing, enchanting, chests, etc.
 *
 * Params: { "slot": int,
 *           "button": int (0=left, 1=right, default 0),
 *           "action": "pickup"|"quick_move"|"swap"|"throw"|"clone" (default "pickup") }
 * Returns: { "clicked": bool, "slot": int }
 */
public class ClickSlotHandler implements ICommandHandler {

    @Override
    public String getCommand() {
        return "click_slot";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        LocalPlayer player = HandlerUtils.requirePlayer();
        JsonObject result = new JsonObject();

        if (player == null) {
            result.addProperty("clicked", false);
            result.addProperty("reason", "no_player");
            return result;
        }
        Minecraft client = Minecraft.getInstance();

        if (!params.has("slot")) {
            result.addProperty("clicked", false);
            result.addProperty("reason", "missing_slot");
            return result;
        }

        int slot = params.get("slot").getAsInt();
        int button = params.has("button") ? params.get("button").getAsInt() : 0;
        String actionStr = params.has("action") ? params.get("action").getAsString() : "pickup";

        ContainerInput action = switch (actionStr.toLowerCase()) {
            case "quick_move" -> ContainerInput.QUICK_MOVE;
            case "swap" -> ContainerInput.SWAP;
            case "throw" -> ContainerInput.THROW;
            case "clone" -> ContainerInput.CLONE;
            default -> ContainerInput.PICKUP;
        };

        int syncId = player.containerMenu.containerId;

        if (slot < 0 || slot >= player.containerMenu.slots.size()) {
            result.addProperty("clicked", false);
            result.addProperty("reason", "slot_out_of_range");
            result.addProperty("max_slot", player.containerMenu.slots.size() - 1);
            return result;
        }

        client.gameMode.handleContainerInput(syncId, slot, button, action, player);

        result.addProperty("clicked", true);
        result.addProperty("slot", slot);
        result.addProperty("button", button);
        result.addProperty("action", actionStr);

        EmmaBridgeMod.LOGGER.info("[Emma Bridge] click_slot: slot={} button={} action={}",
                slot, button, actionStr);
        return result;
    }
}
