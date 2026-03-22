package com.emma.bridge.events;

import com.emma.bridge.BridgeConfig;
import com.emma.bridge.BridgeServer;
import com.emma.bridge.websocket.JsonProtocol;
import com.google.gson.JsonObject;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Debounced position tracker. Only emits position_update events when:
 * 1. Enough time has passed (configurable interval)
 * 2. Player has moved beyond the deadzone threshold
 */
public class PositionTracker {

    private Vec3 lastReportedPos = Vec3.ZERO;
    private float lastYaw = 0;
    private float lastPitch = 0;
    private long lastReportTime = 0;

    public void tick(LocalPlayer player, BridgeServer ws) {
        long now = System.currentTimeMillis();
        long interval = BridgeConfig.getPositionUpdateIntervalMs();
        if (now - lastReportTime < interval) return;

        Vec3 pos = player.position();
        float yaw = player.getYRot();
        float pitch = player.getXRot();

        float deadzone = BridgeConfig.getPositionDeadzone();
        boolean posChanged = pos.distanceToSqr(lastReportedPos) > deadzone * deadzone;
        boolean rotChanged = Math.abs(yaw - lastYaw) > 1.0f || Math.abs(pitch - lastPitch) > 1.0f;

        if (!posChanged && !rotChanged) return;

        lastReportedPos = pos;
        lastYaw = yaw;
        lastPitch = pitch;
        lastReportTime = now;

        JsonObject data = new JsonObject();
        data.addProperty("x", pos.x);
        data.addProperty("y", pos.y);
        data.addProperty("z", pos.z);
        data.addProperty("yaw", yaw);
        data.addProperty("pitch", pitch);
        data.addProperty("on_ground", player.onGround());

        ws.broadcastEvent(JsonProtocol.event("position_update", data));
    }
}
