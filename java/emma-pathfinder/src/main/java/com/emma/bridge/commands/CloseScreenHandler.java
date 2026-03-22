package com.emma.bridge.commands;

import com.emma.bridge.EmmaBridgeMod;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Handles "close_screen" command — closes the currently open screen/GUI.
 *
 * Params: {} (none)
 * Returns: { "closed": bool }
 */
public class CloseScreenHandler implements ICommandHandler {

    @Override
    public String getCommand() {
        return "close_screen";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        JsonObject result = new JsonObject();

        if (player == null) {
            result.addProperty("closed", false);
            result.addProperty("reason", "no_player");
            return result;
        }

        if (client.screen == null) {
            result.addProperty("closed", false);
            result.addProperty("reason", "no_screen_open");
            return result;
        }

        player.closeContainer();

        result.addProperty("closed", true);
        EmmaBridgeMod.LOGGER.info("[Emma Bridge] close_screen");
        return result;
    }
}
