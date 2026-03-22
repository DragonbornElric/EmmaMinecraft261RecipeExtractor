package com.emma.bridge.events;

import emmatone.api.EmmatoneAPI;
import emmatone.api.IEmmatone;
import com.emma.bridge.BridgeServer;
import com.emma.bridge.EmmaBridgeMod;
import com.emma.bridge.commands.TaskRegistry;
import com.emma.bridge.websocket.JsonProtocol;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

/**
 * Listens for Emmatone task completion and failure events.
 *
 * Polls Emmatone's pathing state each tick and detects transitions
 * between "pathing" and "not pathing" to emit task_complete/task_failed events.
 *
 * Uses {@link TaskRegistry} to correlate events back to the original command's
 * task_id and task_type.
 */
public class TaskListener {

    private boolean wasPathing = false;
    private long pathingStartTime = 0;

    /** Ticks since pathing stopped but we're waiting to confirm completion. */
    private int cooldownTicks = 0;

    /** Grace period after task registration before monitoring starts (ticks). */
    private int registrationGraceTicks = 0;
    private static final int REGISTRATION_GRACE = 5; // ~250ms at 20 TPS

    /** How many ticks to wait after pathing stops before declaring done (debounce). */
    private static final int COMPLETION_COOLDOWN = 10; // ~500ms at 20 TPS

    /**
     * Called every tick. Detects Emmatone pathing state transitions and correlates
     * them with TaskRegistry to emit task_complete/task_failed with correct IDs.
     *
     * EmmaClef tasks are EXCLUDED — they chain multiple Emmatone operations
     * (mine → craft → place → open container → craft again) so Emmatone going
     * idle between subtasks does NOT mean the overall task is done.
     * EmmaClef completion is reported by EmmaClefEventReporter via TaskFinishedEvent.
     */
    public void tick(BridgeServer ws) {
        // Skip if no active task
        if (!TaskRegistry.hasActiveTask()) {
            registrationGraceTicks = 0;
            return;
        }

        // Skip Emmatone-based monitoring for EmmaClef tasks — they manage their
        // own lifecycle through EmmaClef's internal TaskFinishedEvent.
        String taskType = TaskRegistry.getActiveTaskType();
        if (taskType != null && taskType.startsWith("emmaclef:")) return;

        // Grace period after registration — gives the task time to start
        // its Emmatone process before we check isProcessActive.
        if (registrationGraceTicks < REGISTRATION_GRACE) {
            registrationGraceTicks++;
            return;
        }

        // Check player death → task_failed
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && player.isDeadOrDying()) {
            emitTaskFailed(ws, "player_died");
            wasPathing = false;
            cooldownTicks = 0;
            return;
        }

        IEmmatone emmatone = EmmatoneAPI.getProvider().getPrimaryEmmatone();
        boolean isPathing = emmatone.getPathingBehavior().isPathing();

        // Also check if any process is active (goal, mine, builder)
        boolean isProcessActive = isAnyProcessActive(emmatone);

        // Detect start of pathing
        if (isPathing && !wasPathing) {
            pathingStartTime = System.currentTimeMillis();
            cooldownTicks = 0;
            EmmaBridgeMod.LOGGER.debug("[Emma Bridge] Emmatone started pathing");
        }

        // Detect pathing stopped
        if (!isPathing && wasPathing) {
            // Start cooldown — Emmatone may be recalculating path
            cooldownTicks = 1;
        }

        // Count cooldown ticks
        if (cooldownTicks > 0 && !isPathing) {
            cooldownTicks++;

            if (cooldownTicks >= COMPLETION_COOLDOWN) {
                cooldownTicks = 0;

                if (TaskRegistry.hasActiveTask()) {
                    if (!isProcessActive) {
                        // Process finished — check if goal was reached
                        boolean atGoal = isAtGoal(emmatone);
                        long durationMs = System.currentTimeMillis() - pathingStartTime;

                        if (atGoal) {
                            emitTaskComplete(ws, durationMs);
                        } else {
                            emitTaskFailed(ws, "path_failed");
                        }
                    }
                    // If process is still active, it might be mining/building
                    // (which pause pathing between segments) — don't emit yet
                }
            }
        } else if (isPathing) {
            cooldownTicks = 0;
        }

        // Detect process becoming inactive while we have a registered task
        // (handles mine/build completion where pathing may have already stopped)
        if (!isProcessActive && !isPathing && cooldownTicks == 0
                && TaskRegistry.hasActiveTask()) {
            long durationMs = System.currentTimeMillis() - TaskRegistry.getActiveTaskStartTime();
            emitTaskComplete(ws, durationMs);
        }

        wasPathing = isPathing;
    }

    private boolean isAnyProcessActive(IEmmatone emmatone) {
        if (emmatone.getCustomGoalProcess().isActive()) return true;
        if (emmatone.getMineProcess().isActive()) return true;
        if (emmatone.getBuilderProcess().isActive()) return true;
        return false;
    }

    private boolean isAtGoal(IEmmatone emmatone) {
        var goal = emmatone.getCustomGoalProcess().getGoal();
        if (goal == null) return true; // No goal = we're done

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return false;

        BlockPos playerPos = new BlockPos(
                (int) player.getX(), (int) player.getY(), (int) player.getZ());
        return goal.isInGoal(playerPos);
    }

    private void emitTaskComplete(BridgeServer ws, long durationMs) {
        JsonObject data = new JsonObject();
        data.addProperty("task_id", TaskRegistry.getActiveTaskId());
        data.addProperty("task_type", TaskRegistry.getActiveTaskType());
        data.addProperty("duration_ms", durationMs);

        ws.broadcastEvent(JsonProtocol.event("task_complete", data));
        EmmaBridgeMod.LOGGER.info("[Emma Bridge] Task complete: {} {} ({}ms)",
                TaskRegistry.getActiveTaskType(), TaskRegistry.getActiveTaskId(), durationMs);
        TaskRegistry.clearTask();

        // Smoothly recenter head pitch to level after task finishes
        HeadRecenter.trigger();
    }

    private void emitTaskFailed(BridgeServer ws, String reason) {
        long durationMs = System.currentTimeMillis() - TaskRegistry.getActiveTaskStartTime();

        JsonObject data = new JsonObject();
        data.addProperty("task_id", TaskRegistry.getActiveTaskId());
        data.addProperty("task_type", TaskRegistry.getActiveTaskType());
        data.addProperty("reason", reason);
        data.addProperty("duration_ms", durationMs);

        ws.broadcastEvent(JsonProtocol.event("task_failed", data));

        // Smoothly recenter head pitch to level after task fails
        HeadRecenter.trigger();

        EmmaBridgeMod.LOGGER.warn("[Emma Bridge] Task failed: {} {} — {} ({}ms)",
                TaskRegistry.getActiveTaskType(), TaskRegistry.getActiveTaskId(), reason, durationMs);
        TaskRegistry.clearTask();
    }
}
