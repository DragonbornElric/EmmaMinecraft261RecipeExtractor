package com.emma.bridge.commands;

import com.emma.bridge.EmmaBridgeMod;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Handles "camera_teleport" command — teleports the spectator account to
 * exact coordinates with exact rotation. Only available in camera mode.
 *
 * No pathfinding involved — instant position set. The camera account must
 * be in spectator mode on the server.
 *
 * Params: { "x": double, "y": double, "z": double, "yaw": float, "pitch": float }
 * Returns: {}
 */
public class CameraHandler implements ICommandHandler {

    @Override
    public String getCommand() {
        return "camera_teleport";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        double x = params.get("x").getAsDouble();
        double y = params.get("y").getAsDouble();
        double z = params.get("z").getAsDouble();
        float yaw = params.has("yaw") ? params.get("yaw").getAsFloat() : 0f;
        float pitch = params.has("pitch") ? params.get("pitch").getAsFloat() : 0f;

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            throw new RuntimeException("No player entity — not in a world");
        }

        // Spectator mode allows free-form position setting
        player.setPos(x, y, z);
        player.setYRot(yaw);
        player.setXRot(pitch);

        EmmaBridgeMod.LOGGER.debug("[Emma Bridge] Camera teleport: ({}, {}, {}) yaw={} pitch={}",
                x, y, z, yaw, pitch);

        return new JsonObject(); // empty success
    }
}
