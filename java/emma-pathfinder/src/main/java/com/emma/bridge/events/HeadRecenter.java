package com.emma.bridge.events;

import com.emma.bridge.EmmaBridgeMod;
import com.emma.bridge.commands.TaskRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * Smoothly recenters the player's head pitch to level (0°) after a task completes.
 *
 * <p>When a Emmatone or EmmaClef task finishes, the player is often looking down
 * at the ground (mining, gathering, building). This utility smoothly interpolates
 * pitch back to 0° over ~0.5 seconds so Emma doesn't awkwardly stare at the floor.</p>
 *
 * <p>The recenter is cancelled if:</p>
 * <ul>
 *   <li>A new task starts (TaskRegistry becomes active)</li>
 *   <li>The interpolation completes (pitch reaches ~0°)</li>
 *   <li>The player is dead or null</li>
 * </ul>
 *
 * <p>Phase 46 — Head Recenter on Task Completion.</p>
 */
public class HeadRecenter {

    /** Whether a recenter is currently in progress. */
    private static boolean active = false;

    /** Ticks remaining in the recentering animation. */
    private static int ticksRemaining = 0;

    /** The pitch we started from. */
    private static float startPitch = 0f;

    /** Duration of the recentering in ticks (~150ms at 20 TPS). */
    private static final int RECENTER_DURATION_TICKS = 3;

    /** Pitch threshold — if already within this range of 0°, skip recentering. */
    private static final float PITCH_DEADZONE = 3.0f;

    /** Target pitch (0° = level, negative = looking up, positive = looking down). */
    private static final float TARGET_PITCH = 0.0f;

    /**
     * Trigger a head recenter. Call this when a task completes or fails.
     * If the head is already near level, this is a no-op.
     */
    public static void trigger() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || player.isDeadOrDying()) return;

        float currentPitch = player.getXRot();

        // Skip if already looking roughly level
        if (Math.abs(currentPitch - TARGET_PITCH) < PITCH_DEADZONE) {
            EmmaBridgeMod.LOGGER.debug("[HeadRecenter] Pitch {:.1f}° already near level, skipping",
                    currentPitch);
            return;
        }

        startPitch = currentPitch;
        ticksRemaining = RECENTER_DURATION_TICKS;
        active = true;

        EmmaBridgeMod.LOGGER.info("[HeadRecenter] Recentering from {:.1f}° to 0° over {}t",
                currentPitch, RECENTER_DURATION_TICKS);
    }

    /**
     * Called every END_CLIENT_TICK. Smoothly interpolates pitch toward 0°.
     * Should be called AFTER TravelLookOverride so it doesn't conflict during
     * active travel, and TravelLookOverride already guards on task type + pathing.
     */
    public static void tick() {
        if (!active) return;

        // Cancel if a new task has started — don't fight Emmatone/EmmaClef
        if (TaskRegistry.hasActiveTask()) {
            active = false;
            ticksRemaining = 0;
            EmmaBridgeMod.LOGGER.debug("[HeadRecenter] Cancelled — new task started");
            return;
        }

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || player.isDeadOrDying()) {
            active = false;
            ticksRemaining = 0;
            return;
        }

        ticksRemaining--;

        if (ticksRemaining <= 0) {
            // Final tick — snap to exact target
            player.setXRot(TARGET_PITCH);
            active = false;
            EmmaBridgeMod.LOGGER.debug("[HeadRecenter] Complete — pitch set to 0°");
            return;
        }

        // Ease-out interpolation: faster at start, slower near target
        // progress goes from 0.0 (just started) to 1.0 (done)
        float progress = 1.0f - ((float) ticksRemaining / RECENTER_DURATION_TICKS);

        // Smooth-step ease-out: 1 - (1-t)^2
        float eased = 1.0f - (1.0f - progress) * (1.0f - progress);

        float newPitch = Mth.lerp(eased, startPitch, TARGET_PITCH);
        player.setXRot(newPitch);
    }

    /**
     * Whether a recenter animation is currently running.
     */
    public static boolean isActive() {
        return active;
    }

    /**
     * Force-cancel any in-progress recentering (e.g., if player manually looks around).
     */
    public static void cancel() {
        active = false;
        ticksRemaining = 0;
    }
}
