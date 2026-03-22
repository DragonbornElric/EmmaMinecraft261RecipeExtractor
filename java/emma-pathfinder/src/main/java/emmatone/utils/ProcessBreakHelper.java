/*
 * This file is part of Emmatone.
 *
 * Emmatone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Emmatone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Emmatone.  If not, see <https://www.gnu.org/licenses/>.
 */

package emmatone.utils;

import emmatone.api.EmmatoneAPI;
import emmatone.api.utils.IPlayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

/**
 * Shared break state machine for Emmatone process-level code.
 * <p>
 * Replaces the old BlockBreakHelper + InputOverrideHandler pipeline with explicit
 * BlockPos + Direction parameters. Same first-tick/continue-tick pattern as
 * {@link emmatone.pathing.movement.Movement#executeBreak Movement.executeBreak()}.
 * <p>
 * Usage in a process onTick():
 * <pre>
 * if (shouldBreak) {
 *     breakHelper.tickBreak(pos, face);
 * } else {
 *     breakHelper.stopBreaking();
 * }
 * </pre>
 */
public class ProcessBreakHelper {

    private static final int BASE_BREAK_DELAY = 5;

    private final IPlayerContext ctx;
    private boolean breaking;
    private int breakDelayTimer;

    public ProcessBreakHelper(IPlayerContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Tick the block-breaking state machine for a specific block position.
     * First tick: starts breaking (clickBlock). Subsequent ticks: continues
     * breaking (onPlayerDamageBlock). Automatically handles delay after
     * a block breaks.
     *
     * @param pos  the block position to break
     * @param face the block face to break from
     */
    public void tickBreak(BlockPos pos, Direction face) {
        if (breakDelayTimer > 0) {
            breakDelayTimer--;
            return;
        }

        ctx.playerController().setHittingBlock(breaking);

        if (ctx.playerController().hasBrokenBlock()) {
            // Start breaking a new block (or first tick)
            ctx.playerController().syncHeldItem();
            ctx.playerController().clickBlock(pos, face);
            ctx.player().swing(InteractionHand.MAIN_HAND);
        } else {
            // Continue breaking the current block
            if (ctx.playerController().onPlayerDamageBlock(pos, face)) {
                ctx.player().swing(InteractionHand.MAIN_HAND);
            }
            if (ctx.playerController().hasBrokenBlock()) {
                // Block broken this tick
                breakDelayTimer = EmmatoneAPI.getSettings().blockBreakSpeed.value - BASE_BREAK_DELAY;
                ctx.minecraft().gameMode.destroyDelay = 0;
            }
        }

        breaking = !ctx.playerController().hasBrokenBlock();
        ctx.playerController().setHittingBlock(false);
    }

    /**
     * Stop any ongoing block breaking. Call when the process no longer wants
     * to break, or on cleanup/cancel.
     */
    public void stopBreaking() {
        if (ctx.player() != null && breaking) {
            ctx.playerController().setHittingBlock(false);
            ctx.playerController().resetBlockRemoving();
        }
        breaking = false;
        breakDelayTimer = 0;
    }

    /** Is a break currently in progress? */
    public boolean isBreaking() {
        return breaking;
    }

    /**
     * Reset all state. Call on process cleanup (replaces clearAllKeys() for
     * the breaking portion of process state).
     */
    public void reset() {
        stopBreaking();
    }

    /**
     * Compute the block face nearest to the player's eye position.
     * Same as Movement.computeFace().
     */
    public Direction computeFace(BlockPos pos) {
        Vec3 eye = ctx.player().getEyePosition(1.0f);
        double dx = eye.x - (pos.getX() + 0.5);
        double dy = eye.y - (pos.getY() + 0.5);
        double dz = eye.z - (pos.getZ() + 0.5);
        double ax = Math.abs(dx);
        double ay = Math.abs(dy);
        double az = Math.abs(dz);
        if (ax >= ay && ax >= az) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        } else if (ay >= az) {
            return dy > 0 ? Direction.UP : Direction.DOWN;
        } else {
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
    }
}
