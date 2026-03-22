package com.emma.bridge.events;

import com.google.gson.JsonObject;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * Tracks player head rotation per tick and records abnormal snaps
 * (large yaw/pitch changes) to the CombatLog ring buffer.
 *
 * Thresholds:
 *   - |delta_yaw| > 30°  → snap event
 *   - |delta_pitch| > 20° → snap event
 *
 * Real players rarely exceed 15°/tick yaw or 10°/tick pitch in normal play.
 * Snaps above these thresholds indicate programmatic rotation (KillAura,
 * LookHelper, TravelLookOverride, HeadRecenter, Emmatone pathing).
 */
public class RotationTracker {

    private static final float YAW_THRESHOLD = 30.0f;
    private static final float PITCH_THRESHOLD = 20.0f;

    private float prevYaw = Float.NaN;
    private float prevPitch = Float.NaN;

    /**
     * Called every tick by EventReporter. Compares current rotation
     * to previous tick and records snaps to CombatLog.
     */
    public void tick(LocalPlayer player) {
        float yaw = player.getYRot();
        float pitch = player.getXRot();

        if (Float.isNaN(prevYaw)) {
            // First tick — initialize
            prevYaw = yaw;
            prevPitch = pitch;
            return;
        }

        float deltaYaw = Mth.wrapDegrees(yaw - prevYaw);
        float deltaPitch = pitch - prevPitch;

        if (Math.abs(deltaYaw) > YAW_THRESHOLD || Math.abs(deltaPitch) > PITCH_THRESHOLD) {
            JsonObject data = new JsonObject();
            data.addProperty("yaw", yaw);
            data.addProperty("pitch", pitch);
            data.addProperty("delta_yaw", deltaYaw);
            data.addProperty("delta_pitch", deltaPitch);
            // Source is hard to determine at tick time — tag as "unknown" for now.
            // Stage 6+ will add source tagging from KillAura/LookHelper/etc.
            data.addProperty("source", "unknown");
            CombatLog.getInstance().record("rotation_snap", data);
        }

        prevYaw = yaw;
        prevPitch = pitch;
    }

    /** Reset state (e.g., on respawn). */
    public void reset() {
        prevYaw = Float.NaN;
        prevPitch = Float.NaN;
    }
}
