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

package emmatone.pathing.movement;

import emmatone.Emmatone;
import emmatone.api.IEmmatone;
import emmatone.api.EmmatoneAPI;
import emmatone.api.pathing.movement.IMovement;
import emmatone.api.pathing.movement.MovementStatus;
import emmatone.api.utils.*;
import emmatone.api.utils.input.Input;
import emmatone.behavior.PathingBehavior;
import emmatone.utils.BlockStateInterface;
import emmatone.utils.ControlledInput;
import com.emma.bridge.control.BlockInteraction;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public abstract class Movement implements IMovement, MovementHelper {

    public static final Direction[] HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN};

    protected final IEmmatone emmatone;
    protected final IPlayerContext ctx;

    private MovementState currentState = new MovementState().setStatus(MovementStatus.PREPPING);

    protected final BetterBlockPos src;

    protected final BetterBlockPos dest;

    /**
     * The positions that need to be broken before this movement can ensue
     */
    protected final BetterBlockPos[] positionsToBreak;

    /**
     * The position where we need to place a block before this movement can ensue
     */
    protected final BetterBlockPos positionToPlace;

    private Double cost;

    // Break/place state machines — persist across ticks within one movement step.
    // Phase 3: All movement-path interactions use MAIN_HAND exclusively.
    private boolean wasBreaking;
    private int breakDelayTimer;
    private int placeDelayTimer;
    private static final int BASE_BREAK_DELAY = 1;
    private static final int BASE_PLACE_DELAY = 1;

    public List<BlockPos> toBreakCached = null;
    public List<BlockPos> toPlaceCached = null;
    public List<BlockPos> toWalkIntoCached = null;

    private Set<BetterBlockPos> validPositionsCached = null;

    private Boolean calculatedWhileLoaded;

    protected Movement(IEmmatone emmatone, BetterBlockPos src, BetterBlockPos dest, BetterBlockPos[] toBreak, BetterBlockPos toPlace) {
        this.emmatone = emmatone;
        this.ctx = emmatone.getPlayerContext();
        this.src = src;
        this.dest = dest;
        this.positionsToBreak = toBreak;
        this.positionToPlace = toPlace;
    }

    protected Movement(IEmmatone emmatone, BetterBlockPos src, BetterBlockPos dest, BetterBlockPos[] toBreak) {
        this(emmatone, src, dest, toBreak, null);
    }

    public double getCost() throws NullPointerException {
        return cost;
    }

    public double getCost(CalculationContext context) {
        if (cost == null) {
            cost = calculateCost(context);
        }
        return cost;
    }

    public abstract double calculateCost(CalculationContext context);

    public double recalculateCost(CalculationContext context) {
        cost = null;
        return getCost(context);
    }

    public void override(double cost) {
        this.cost = cost;
    }

    protected abstract Set<BetterBlockPos> calculateValidPositions();

    public Set<BetterBlockPos> getValidPositions() {
        if (validPositionsCached == null) {
            validPositionsCached = calculateValidPositions();
            Objects.requireNonNull(validPositionsCached);
        }
        return validPositionsCached;
    }

    protected boolean playerInValidPosition() {
        return getValidPositions().contains(ctx.playerFeet()) || getValidPositions().contains(((PathingBehavior) emmatone.getPathingBehavior()).pathStart());
    }

    /**
     * Handles the execution of the latest Movement
     * State, and offers a Status to the calling class.
     *
     * @return Status
     */
    @Override
    public MovementStatus update() {
        ctx.player().getAbilities().flying = false;
        currentState = updateState(currentState);
        if (MovementHelper.isLiquid(ctx, ctx.playerFeet()) && ctx.player().position().y < dest.y + 0.6) {
            currentState.setInput(Input.JUMP, true);
        }
        if (ctx.player().isInWall()) {
            // Compute head block pos directly — no raycast
            BetterBlockPos headPos = new BetterBlockPos(BlockPos.containing(ctx.player().getEyePosition(1.0f)));
            if (!MovementHelper.canWalkThrough(ctx, headPos)) {
                MovementHelper.switchToBestToolFor(ctx, BlockStateInterface.get(ctx, headPos));
                currentState.setInteraction(new MovementState.InteractionIntent.BreakBlock(headPos));
            }
        }

        // LookBehavior uses direct Mojang rotation APIs (setYRot/setXRot), not raycasts.
        // Kept for cosmetic facing during travel. Does NOT gate any interaction.
        currentState.getTarget().getRotation().ifPresent(rotation ->
                emmatone.getLookBehavior().updateTarget(
                        rotation,
                        currentState.getTarget().hasToForceRotations()));

        // Write movement intents to ControlledInput (replaces InputOverrideHandler forwarding).
        // ControlledInput.tick() runs during tickMovement() and feeds travel() — same pipeline
        // as Mojang's mob MoveControl. No virtual KeyBinding state to leak.
        Map<Input, Boolean> inputs = currentState.getInputStates();
        if (ctx.player().input instanceof ControlledInput controlled) {
            controlled.setDesired(new net.minecraft.world.entity.player.Input(
                    inputs.getOrDefault(Input.MOVE_FORWARD, false),
                    inputs.getOrDefault(Input.MOVE_BACK, false),
                    inputs.getOrDefault(Input.MOVE_LEFT, false),
                    inputs.getOrDefault(Input.MOVE_RIGHT, false),
                    inputs.getOrDefault(Input.JUMP, false),
                    inputs.getOrDefault(Input.SNEAK, false),
                    inputs.getOrDefault(Input.SPRINT, false)
            ));
        }
        inputs.clear();

        // Dispatch interaction intent — strict 1:1 mapping, no fallbacks.
        // Each intent maps to exactly one Mojang API path.
        MovementState.InteractionIntent intent = currentState.getInteraction();
        if (intent instanceof MovementState.InteractionIntent.BreakBlock bb) {
            executeBreak(bb.pos());
        } else if (intent instanceof MovementState.InteractionIntent.PlaceBlock pb) {
            executePlace(pb.placeAt(), pb.against(), pb.face());
        } else if (intent instanceof MovementState.InteractionIntent.InteractBlock ib) {
            executeInteractBlock(ib.target(), ib.face());
        } else if (intent instanceof MovementState.InteractionIntent.UseItem) {
            executeUseItem();
        } else if (wasBreaking) {
            stopBreaking(); // no intent this tick, stop any ongoing break
        }
        currentState.clearInteraction();

        // If the current status indicates a completed movement
        if (currentState.getStatus().isComplete()) {
            if (ctx.player().input instanceof ControlledInput controlled) {
                controlled.setDesired(ControlledInput.NONE);
            }
            stopBreaking();
        }

        return currentState.getStatus();
    }

    protected boolean prepared(MovementState state) {
        if (state.getStatus() == MovementStatus.WAITING) {
            return true;
        }
        boolean somethingInTheWay = false;
        for (BetterBlockPos blockPos : positionsToBreak) {
            if (!ctx.world().getEntitiesOfClass(FallingBlockEntity.class, new AABB(0, 0, 0, 1, 1.1, 1).move(blockPos)).isEmpty() && Emmatone.settings().pauseMiningForFallingBlocks.value) {
                return false;
            }
            if (!MovementHelper.canWalkThrough(ctx, blockPos)) {
                somethingInTheWay = true;
                MovementHelper.switchToBestToolFor(ctx, BlockStateInterface.get(ctx, blockPos));
                // Pure distance check — no raycasts, no rotation gating.
                // Server validates reach on the actual break API call.
                double dist = ctx.player().getEyePosition(1.0f).distanceTo(
                        Vec3.atCenterOf(blockPos));
                if (dist <= ctx.playerController().getBlockReachDistance()) {
                    state.setInteraction(new MovementState.InteractionIntent.BreakBlock(blockPos));
                }
                return false;
            }
        }
        if (somethingInTheWay) {
            state.setStatus(MovementStatus.UNREACHABLE);
            return true;
        }
        return true;
    }

    @Override
    public boolean safeToCancel() {
        return safeToCancel(currentState);
    }

    protected boolean safeToCancel(MovementState currentState) {
        return true;
    }

    @Override
    public BetterBlockPos getSrc() {
        return src;
    }

    @Override
    public BetterBlockPos getDest() {
        return dest;
    }

    @Override
    public void reset() {
        currentState = new MovementState().setStatus(MovementStatus.PREPPING);
        stopBreaking();
        placeDelayTimer = 0;
    }

    // ─── Direct Mojang API dispatch ─────────────────────────────────────

    /**
     * Break a block at a known position. Replicates BlockBreakHelper state machine
     * with explicit BlockPos instead of raycast-based target detection.
     */
    private void executeBreak(BlockPos pos) {
        if (breakDelayTimer > 0) {
            breakDelayTimer--;
            return;
        }
        Direction face = computeFace(pos);
        ctx.playerController().setHittingBlock(wasBreaking);
        if (ctx.playerController().hasBrokenBlock()) {
            // Start breaking a new block
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
        wasBreaking = !ctx.playerController().hasBrokenBlock();
        ctx.playerController().setHittingBlock(false);
    }

    /**
     * Place a block at placeAt against an adjacent block. Exactly one API path:
     * processRightClickBlock. No fallback to processRightClick.
     */
    private void executePlace(BlockPos placeAt, BlockPos against, Direction face) {
        if (placeDelayTimer > 0) {
            placeDelayTimer--;
            return;
        }
        InteractionResult result = BlockInteraction.placeBlock(ctx, against, face);
        if (result == InteractionResult.SUCCESS) {
            placeDelayTimer = Emmatone.settings().rightClickSpeed.value - BASE_PLACE_DELAY;
        }
    }

    /**
     * Interact with an existing block (doors, gates, water bucket). Exactly one API path:
     * processRightClickBlock targeting the block itself. No fallback.
     */
    private void executeInteractBlock(BlockPos target, Direction face) {
        BlockInteraction.placeBlock(ctx, target, face);
    }

    /**
     * Use held item without block target (eating, bows, pearls). Exactly one API path:
     * processRightClick. No fallback to processRightClickBlock.
     * Unused in Phase 3 Movement path — included for Phase 4.
     */
    private void executeUseItem() {
        ctx.playerController().processRightClick(
                ctx.player(), ctx.world(), InteractionHand.MAIN_HAND);
    }

    /**
     * Stop any ongoing block breaking. Called on movement completion, reset,
     * and from PathExecutor.cancel().
     */
    public void stopBreaking() {
        if (ctx.player() != null && wasBreaking) {
            ctx.playerController().setHittingBlock(false);
            ctx.playerController().resetBlockRemoving();
        }
        wasBreaking = false;
        breakDelayTimer = 0;
    }

    /**
     * Compute the block face nearest to the player's eye position.
     */
    protected Direction computeFace(BlockPos pos) {
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

    // ─── End direct API dispatch ────────────────────────────────────────

    /**
     * Calculate latest movement state. Gets called once a tick.
     *
     * @param state The current state
     * @return The new state
     */
    public MovementState updateState(MovementState state) {
        if (!prepared(state)) {
            return state.setStatus(MovementStatus.PREPPING);
        } else if (state.getStatus() == MovementStatus.PREPPING) {
            state.setStatus(MovementStatus.WAITING);
        }

        if (state.getStatus() == MovementStatus.WAITING) {
            state.setStatus(MovementStatus.RUNNING);
        }

        return state;
    }

    @Override
    public BlockPos getDirection() {
        return getDest().subtract(getSrc());
    }

    public void checkLoadedChunk(CalculationContext context) {
        calculatedWhileLoaded = context.bsi.worldContainsLoadedChunk(dest.x, dest.z);
    }

    @Override
    public boolean calculatedWhileLoaded() {
        return calculatedWhileLoaded;
    }

    @Override
    public void resetBlockCache() {
        toBreakCached = null;
        toPlaceCached = null;
        toWalkIntoCached = null;
    }

    public List<BlockPos> toBreak(BlockStateInterface bsi) {
        if (toBreakCached != null) {
            return toBreakCached;
        }
        List<BlockPos> result = new ArrayList<>();
        for (BetterBlockPos positionToBreak : positionsToBreak) {
            if (!MovementHelper.canWalkThrough(bsi, positionToBreak.x, positionToBreak.y, positionToBreak.z)) {
                result.add(positionToBreak);
            }
        }
        toBreakCached = result;
        return result;
    }

    public List<BlockPos> toPlace(BlockStateInterface bsi) {
        if (toPlaceCached != null) {
            return toPlaceCached;
        }
        List<BlockPos> result = new ArrayList<>();
        if (positionToPlace != null && !MovementHelper.canWalkOn(bsi, positionToPlace.x, positionToPlace.y, positionToPlace.z)) {
            result.add(positionToPlace);
        }
        toPlaceCached = result;
        return result;
    }

    public List<BlockPos> toWalkInto(BlockStateInterface bsi) { // overridden by movementdiagonal
        if (toWalkIntoCached == null) {
            toWalkIntoCached = new ArrayList<>();
        }
        return toWalkIntoCached;
    }

    public BlockPos[] toBreakAll() {
        return positionsToBreak;
    }
}
