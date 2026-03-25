package com.emma.bridge.goap.actions;

import emmatone.api.EmmatoneAPI;
import emmatone.api.IEmmatone;
import emmatone.api.behavior.IPathingBehavior;
import emmatone.api.pathing.goals.GoalBlock;
import emmatone.api.process.ICustomGoalProcess;
import emmatone.api.process.IMineProcess;
import emmatone.api.utils.VecUtils;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

/**
 * Shared Emmatone navigation utilities for GOAP actions.
 * Single point of access for the verbose EmmatoneAPI call chain.
 */
public final class GoapNavHelper {

    private GoapNavHelper() {}

    /** Result of a single tick of navigate-to-block logic. */
    public enum NavResult {
        /** Target is null — caller should go back to a "find" phase. */
        NO_TARGET,
        /** Player arrived within arrival distance. */
        ARRIVED,
        /** Still pathing — no action needed from caller. */
        PATHING,
        /** Navigation timed out. */
        TIMEOUT
    }

    // ── Emmatone shortcuts ──────────────────────────────────────

    public static IEmmatone emmatone() {
        return EmmatoneAPI.getProvider().getPrimaryEmmatone();
    }

    public static IPathingBehavior pathing() {
        return emmatone().getPathingBehavior();
    }

    public static ICustomGoalProcess goalProcess() {
        return emmatone().getCustomGoalProcess();
    }

    public static IMineProcess mineProcess() {
        return emmatone().getMineProcess();
    }

    public static void cancelPathing() {
        pathing().cancelEverything();
    }

    public static boolean isPathing() {
        return pathing().isPathing();
    }

    public static void pathTo(BlockPos pos) {
        goalProcess().setGoalAndPath(new GoalBlock(pos));
    }

    /** Arrival distance for container interactions (crafting table, furnace, chest, etc.). */
    public static final double CONTAINER_ARRIVAL_DIST = 2.0;

    // ── Navigate-to-block tick helper ───────────────────────────

    /**
     * Run one tick of "navigate to a block position" logic.
     *
     * @param player       the local player
     * @param target       target block position (may be null)
     * @param waitTicks    current wait tick counter (caller increments before calling)
     * @param timeoutTicks maximum ticks before timeout (typically 200)
     * @param arrivalDist  arrival distance (squared comparison uses arrivalDist * arrivalDist)
     * @return the navigation result for this tick
     */
    public static NavResult tickNavigateToBlock(LocalPlayer player, BlockPos target,
                                                 int waitTicks, int timeoutTicks,
                                                 double arrivalDist) {
        if (target == null) {
            return NavResult.NO_TARGET;
        }

        double dist = VecUtils.entityDistanceToCenter(player, target);

        if (dist < arrivalDist) {
            cancelPathing();
            return NavResult.ARRIVED;
        }

        if (!isPathing()) {
            pathTo(target);
        }

        if (waitTicks > timeoutTicks) {
            cancelPathing();
            return NavResult.TIMEOUT;
        }

        return NavResult.PATHING;
    }

    /**
     * Convenience overload with default arrival distance of 3.0 and timeout of 200 ticks.
     */
    public static NavResult tickNavigateToBlock(LocalPlayer player, BlockPos target, int waitTicks) {
        return tickNavigateToBlock(player, target, waitTicks, 200, 3.0);
    }
}
