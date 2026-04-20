package com.emma.bridge.goap.actions;

import emmatone.api.EmmatoneAPI;
import emmatone.api.pathing.goals.Goal;
import emmatone.api.pathing.goals.GoalBlock;
import emmatone.api.pathing.goals.GoalXZ;
import emmatone.api.process.ICustomGoalProcess;
import com.emma.bridge.EmmaBridgeMod;
import com.emma.bridge.goap.GoapAction;
import com.emma.bridge.goap.GoalSet;
import com.emma.bridge.goap.WorldState;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

/**
 * GOAP Action: Navigate to a target position using Emmatone pathfinding.
 *
 * Preconditions: always true (scoring returns 0 if no position targets exist)
 * Score: goal priority x inverse distance
 *
 * Looks for dynamic goals with position targets (x/y/z in target JSON).
 * Delegates to Emmatone's CustomGoalProcess for pathfinding.
 * Cancels pathing on deactivation.
 */
public class NavigateToAction extends GoapAction {

    /** Don't navigate to positions closer than this (blocks). */
    private static final float MIN_DISTANCE = 3.0f;
    /** Strong score floor for emergency food-navigation goals. */
    private static final float FOOD_NAV_MIN_SCORE = 4.0f;

    private boolean navigating = false;
    private String targetGoalId = null;
    private int targetX, targetY, targetZ;
    private boolean targetIsXZOnly = false;

    @Override
    public String getName() {
        return "NavigateTo";
    }

    @Override
    public boolean checkPreconditions(WorldState state) {
        return true;
    }

    @Override
    public float computeScore(WorldState state, GoalSet goals) {
        GoalSet.Goal target = findNavigationTarget(state, goals);
        if (target == null) return 0;

        double distance = distanceToTarget(state);

        if (distance < MIN_DISTANCE) return 0;

        boolean foodEmergencyNav = targetGoalId != null && targetGoalId.startsWith("food_");

        if (foodEmergencyNav) {
            // Keep food-recovery navigation competitive even over long distances,
            // otherwise Explore can steal control and cause aimless wandering.
            float urgency = state.foodItemCount <= 0 ? 1.35f : 1.15f;
            float proximityFactor = 1.0f / (1.0f + (float) distance / 64.0f);
            float score = target.priority * urgency * proximityFactor;
            return Math.max(FOOD_NAV_MIN_SCORE, score);
        }

        // Proximity factor: nearby targets score higher
        float proximityFactor = 1.0f / (1.0f + (float) distance / 16.0f);

        return target.priority * proximityFactor;
    }

    @Override
    public void execute(Minecraft client) {
        if (targetGoalId == null) return;

        boolean foodEmergencyNav = targetGoalId.startsWith("food_");
        if (foodEmergencyNav && client.player != null) {
            double dx = targetX - client.player.getX();
            double dy = targetY - client.player.getY();
            double dz = targetZ - client.player.getZ();
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            EmmaBridgeMod.LOGGER.info(
                    "[NavigateTo] Food emergency nav engaged: goal={} target=({},{},{}) dist={}",
                    targetGoalId,
                    targetX,
                    targetY,
                    targetZ,
                    String.format("%.1f", dist));
        }

        ICustomGoalProcess goalProcess = EmmatoneAPI.getProvider()
                .getPrimaryEmmatone()
                .getCustomGoalProcess();

        Goal goal = targetIsXZOnly
            ? new GoalXZ(targetX, targetZ)
            : new GoalBlock(targetX, targetY, targetZ);
        goalProcess.setGoalAndPath(goal);
        navigating = true;
    }

    @Override
    public void tick(Minecraft client) {
        if (!navigating) return;

        // Check if Emmatone finished pathing (reached goal or gave up)
        boolean pathing = EmmatoneAPI.getProvider()
                .getPrimaryEmmatone()
                .getPathingBehavior().isPathing();

        if (!pathing && client.player != null) {
            double dx = client.player.getX() - targetX;
            double dz = client.player.getZ() - targetZ;
            double dist = targetIsXZOnly
                    ? Math.sqrt(dx * dx + dz * dz)
                    : Math.sqrt(dx * dx + (client.player.getY() - targetY) * (client.player.getY() - targetY) + dz * dz);
            if (dist < MIN_DISTANCE) {
                navigating = false;  // Arrived -- score drops, another action wins
                clearTarget();
            } else {
                navigating = false;
                clearTarget();
            }
        }
    }

    @Override
    public void onDeactivated(Minecraft client) {
        if (navigating) {
            EmmatoneAPI.getProvider()
                    .getPrimaryEmmatone()
                    .getPathingBehavior().cancelEverything();
            navigating = false;
        }
        clearTarget();
    }

    @Override
    public boolean isActive() {
        return navigating;
    }

    // -- Collateral + personality -----------------------------------------

    @Override
    public String getPrimaryGoalId() {
        return targetGoalId;
    }

    @Override
    public String personalityCategory() {
        return "exploration";
    }

    // -- Debug ------------------------------------------------------------

    @Override
    public JsonObject getScoreBreakdown(WorldState state, GoalSet goals) {
        JsonObject bd = super.getScoreBreakdown(state, goals);
        bd.addProperty("navigating", navigating);
        bd.addProperty("target_goal", targetGoalId != null ? targetGoalId : "none");
        if (targetGoalId != null) {
            bd.addProperty("target_x", targetX);
            bd.addProperty("target_y", targetY);
            bd.addProperty("target_z", targetZ);
            bd.addProperty("target_xz_only", targetIsXZOnly);
            bd.addProperty("food_emergency_nav", targetGoalId.startsWith("food_"));
        }

        try {
            var emmatone = EmmatoneAPI.getProvider().getPrimaryEmmatone();
            bd.addProperty("emmatone_pathing", emmatone.getPathingBehavior().isPathing());
            var goal = emmatone.getPathingBehavior().getGoal();
            bd.addProperty("emmatone_goal", goal != null ? goal.toString() : "none");
        } catch (Exception ignored) {}

        return bd;
    }

    // -- Target finding ---------------------------------------------------

    /**
     * Find the best dynamic goal with a position target.
     * Caches target coordinates for execute().
     */
    private GoalSet.Goal findNavigationTarget(WorldState state, GoalSet goals) {
        GoalSet.Goal bestTarget = null;
        float bestScore = 0;
        clearTarget();

        for (GoalSet.Goal goal : goals.getGoals()) {
            if (goal.isSurvival()) continue;
            if (goal.target == null) continue;
            if (!goal.target.has("x") || !goal.target.has("z")) continue;

            double tx = goal.target.get("x").getAsDouble();
            boolean xzOnly = !goal.target.has("y") || goal.target.get("y").isJsonNull();
            double ty = xzOnly ? state.posY : goal.target.get("y").getAsDouble();
            double tz = goal.target.get("z").getAsDouble();

            double dx = tx - state.posX;
            double dy = xzOnly ? 0 : ty - state.posY;
            double dz = tz - state.posZ;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

            if (distance < MIN_DISTANCE) continue;

            float score = goal.priority / (1.0f + (float) distance / 16.0f);
            if (score > bestScore) {
                bestScore = score;
                bestTarget = goal;
                targetIsXZOnly = xzOnly;
            }
        }

        if (bestTarget != null) {
            targetGoalId = bestTarget.id;
            targetX = bestTarget.target.get("x").getAsInt();
            targetY = targetIsXZOnly ? (int) Math.round(state.posY) : bestTarget.target.get("y").getAsInt();
            targetZ = bestTarget.target.get("z").getAsInt();
        }

        return bestTarget;
    }

    private double distanceToTarget(WorldState state) {
        double dx = targetX - state.posX;
        double dz = targetZ - state.posZ;
        if (targetIsXZOnly) {
            return Math.sqrt(dx * dx + dz * dz);
        }
        double dy = targetY - state.posY;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private void clearTarget() {
        targetGoalId = null;
        targetX = 0;
        targetY = 0;
        targetZ = 0;
        targetIsXZOnly = false;
    }
}
