package com.emma.bridge.commands;

import com.emma.bridge.EmmaBridgeMod;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * Handles "dismount" command — dismounts from the current vehicle/entity.
 *
 * Params: {} (none)
 * Returns: { "dismounted": bool }
 */
public class DismountHandler implements ICommandHandler {

    @Override
    public String getCommand() {
        return "dismount";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        LocalPlayer player = Minecraft.getInstance().player;
        JsonObject result = new JsonObject();

        if (player == null) {
            result.addProperty("dismounted", false);
            result.addProperty("reason", "no_player");
            return result;
        }

        if (!player.isPassenger()) {
            result.addProperty("dismounted", false);
            result.addProperty("reason", "not_mounted");
            return result;
        }

        String vehicleType = BuiltInRegistries.ENTITY_TYPE.getKey(player.getVehicle().getType()).toString();
        player.stopRiding();

        result.addProperty("dismounted", true);
        result.addProperty("vehicle", vehicleType);
        EmmaBridgeMod.LOGGER.info("[Emma Bridge] dismount from {}", vehicleType);
        return result;
    }
}
