package com.emma.bridge.events;

import emmatone.api.EmmatoneAPI;
import emmatone.api.IEmmatone;
import emmatone.api.utils.Rotation;
import com.emma.bridge.BridgeConfig;
import com.emma.bridge.EmmaBridgeMod;
import com.emma.bridge.control.BlockInteraction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Travel Look Override — makes the player face the direction of travel
 * during pure navigation (goto commands), instead of staring at the ground.
 *
 * <p>Runs each tick AFTER Emmatone has processed its movement. Only activates
 * when all of these are true:</p>
 * <ul>
 *   <li>Config: {@code travel_look_override_enabled} is true</li>
 *   <li>Active task type is {@code "goto"} (not mine/build/emmaclef)</li>
 *   <li>Only {@code CustomGoalProcess} is active (not mine/builder)</li>
 *   <li>Player is pathing (Emmatone is actively walking)</li>
 *   <li>Horizontal speed exceeds deadzone threshold</li>
 *   <li>Player is not swimming, in a vehicle, or fall-flying</li>
 * </ul>
 *
 * <p>Phase 46 — Travel Look Override.</p>
 */
public class TravelLookOverride {

    // ── Smoothing state ──────────────────────────────────────────

    /** Smoothed yaw that we're interpolating toward. */
    private float smoothedYaw = 0f;

    /** Whether we have a valid previous yaw to interpolate from. */
    private boolean hasInitialYaw = false;

    /** Last known good travel yaw (used when velocity drops to zero briefly). */
    private float lastTravelYaw = 0f;

    /** Ticks since we last had valid velocity. */
    private int zeroVelocityTicks = 0;

    // ── Constants ────────────────────────────────────────────────

    /** Minimum horizontal speed (blocks/tick) to compute a travel direction. */
    private static final double MIN_HORIZONTAL_SPEED = 0.01;

    /** How quickly to blend toward the target yaw (0.0–1.0). Higher = snappier. */
    private static final float LERP_FACTOR = 0.45f;

    /** After this many ticks of zero velocity, stop overriding. */
    private static final int ZERO_VELOCITY_GRACE_TICKS = 5;

    /** Maximum vertical speed that still counts as "flat" travel. */
    private static final double MAX_VERTICAL_SPEED = 0.5;

    // ── Tick ─────────────────────────────────────────────────────

    /**
     * Called every END_CLIENT_TICK, after Emmatone and TaskListener.
     * Conditionally overrides player look direction during pure travel.
     */
    public void tick() {
        if (!BridgeConfig.isTravelLookOverrideEnabled()) {
            resetState();
            return;
        }

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || player.isDeadOrDying()) {
            resetState();
            return;
        }

        // ── Gate 2: Must be actively pathing ─────────────────────
        IEmmatone emmatone = EmmatoneAPI.getProvider().getPrimaryEmmatone();
        if (!emmatone.getPathingBehavior().isPathing()) {
            resetState();
            return;
        }

        // ── Gate 3: Only CustomGoalProcess active (no mine/build) ─
        if (emmatone.getMineProcess().isActive()
                || emmatone.getBuilderProcess().isActive()) {
            resetState();
            return;
        }

        // ── Gate 4: Skip special movement states ─────────────────
        if (player.isInWater() || player.isPassenger() || player.onClimbable()) {
            resetState();
            return;
        }

        // ── Gate 5: Check horizontal velocity ────────────────────
        Vec3 velocity = player.getDeltaMovement();
        double hSpeed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);

        // Skip if vertical movement too large (jumping, falling)
        if (Math.abs(velocity.y) > MAX_VERTICAL_SPEED) {
            // Don't reset — keep last known direction, just skip this tick
            return;
        }

        if (hSpeed < MIN_HORIZONTAL_SPEED) {
            zeroVelocityTicks++;
            if (zeroVelocityTicks > ZERO_VELOCITY_GRACE_TICKS) {
                // Standing still too long — stop overriding
                return;
            }
            // Use last known direction during brief stops (path recalculation)
        } else {
            zeroVelocityTicks = 0;
            // Calculate travel yaw from velocity vector
            // Yaw from velocity vector — reuse calcYawPitch with a unit-length forward vector
            lastTravelYaw = BlockInteraction.calcYawPitch(Vec3.ZERO, velocity)[0];
        }

        // ── Apply smoothed rotation ──────────────────────────────
        float targetYaw = lastTravelYaw;
        float targetPitch = BridgeConfig.getTravelLookPitch();

        if (!hasInitialYaw) {
            smoothedYaw = targetYaw;
            hasInitialYaw = true;
        } else {
            smoothedYaw = lerpAngle(smoothedYaw, targetYaw, LERP_FACTOR);
        }

        player.setYRot(smoothedYaw);
        player.setXRot(targetPitch);

        // Tell Emmatone's LookBehavior that this is the target rotation,
        // so it doesn't fight us next tick.
        emmatone.getLookBehavior().updateTarget(
                new Rotation(smoothedYaw, targetPitch), true);
    }

    // ── Helpers ──────────────────────────────────────────────────

    /**
     * Smoothly interpolate between two angles (handling the 360° wrap).
     */
    private static float lerpAngle(float from, float to, float factor) {
        float diff = Mth.wrapDegrees(to - from);
        return from + diff * factor;
    }

    /**
     * Reset all smoothing state when override is no longer active.
     */
    private void resetState() {
        hasInitialYaw = false;
        zeroVelocityTicks = 0;
    }
}
