package com.emma.bridge.commands;

import com.emma.bridge.EmmaBridgeMod;
import com.emma.bridge.control.BlockInteraction;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Handles "look_at" command — rotates the player to face target coordinates.
 *
 * Params: { "x": double, "y": double, "z": double }
 * Returns: { "yaw": float, "pitch": float }
 */
public class LookAtHandler implements ICommandHandler {

    @Override
    public String getCommand() {
        return "look_at";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        double tx = params.get("x").getAsDouble();
        double ty = params.get("y").getAsDouble();
        double tz = params.get("z").getAsDouble();

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            throw new RuntimeException("No player entity");
        }

        Vec3 target = new Vec3(tx, ty, tz);
        float[] rot = BlockInteraction.calcYawPitch(player.getEyePosition(), target);
        player.setYRot(rot[0]);
        player.setXRot(rot[1]);

        EmmaBridgeMod.LOGGER.debug("[Emma Bridge] LookAt: ({}, {}, {}) → yaw={}, pitch={}",
                tx, ty, tz, rot[0], rot[1]);

        JsonObject result = new JsonObject();
        result.addProperty("yaw", rot[0]);
        result.addProperty("pitch", rot[1]);
        return result;
    }
}
