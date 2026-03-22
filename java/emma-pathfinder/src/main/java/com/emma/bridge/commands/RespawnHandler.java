package com.emma.bridge.commands;

import com.emma.bridge.EmmaBridgeMod;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Handles "respawn" command — clicks the respawn button after death.
 * Available in both player and camera modes (shared handler).
 *
 * Params: {} (none)
 * Returns: { respawned: true/false, reason: string }
 */
public class RespawnHandler implements ICommandHandler {

    @Override
    public String getCommand() {
        return "respawn";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;

        JsonObject result = new JsonObject();

        if (player == null) {
            result.addProperty("respawned", false);
            result.addProperty("reason", "no_player");
            return result;
        }

        if (!player.isDeadOrDying()) {
            result.addProperty("respawned", false);
            result.addProperty("reason", "not_dead");
            return result;
        }

        player.respawn();
        EmmaBridgeMod.LOGGER.info("[Emma Bridge] Respawn requested");

        result.addProperty("respawned", true);
        result.addProperty("reason", "ok");
        return result;
    }
}
