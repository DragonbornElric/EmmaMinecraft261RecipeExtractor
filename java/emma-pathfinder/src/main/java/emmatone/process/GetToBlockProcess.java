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

package emmatone.process;

import emmatone.Emmatone;
import emmatone.api.pathing.goals.*;
import emmatone.api.process.IGetToBlockProcess;
import emmatone.api.process.PathingCommand;
import emmatone.api.process.PathingCommandType;
import emmatone.api.utils.BlockOptionalMeta;
import emmatone.api.utils.BlockOptionalMetaLookup;
import emmatone.api.utils.Rotation;
import emmatone.api.utils.RotationUtils;
import emmatone.pathing.movement.CalculationContext;
import emmatone.pathing.movement.MovementHelper;
import emmatone.utils.EmmatoneProcessHelper;
import com.emma.bridge.control.BlockInteraction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;

public final class GetToBlockProcess extends EmmatoneProcessHelper implements IGetToBlockProcess {

    private BlockOptionalMeta gettingTo;
    private List<BlockPos> knownLocations;
    private List<BlockPos> blacklist; // locations we failed to calc to
    private BlockPos start;

    private int tickCount = 0;
    private int arrivalTickCount = 0;

    public GetToBlockProcess(Emmatone emmatone) {
        super(emmatone);
    }

    @Override
    public void getToBlock(BlockOptionalMeta block) {
        onLostControl();
        gettingTo = block;
        start = ctx.playerFeet();
        blacklist = new ArrayList<>();
        arrivalTickCount = 0;
        rescan(new ArrayList<>(), new GetToBlockCalculationContext(false));
    }

    @Override
    public boolean isActive() {
        return gettingTo != null;
    }

    @Override
    public synchronized PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (knownLocations == null) {
            rescan(new ArrayList<>(), new GetToBlockCalculationContext(false));
        }
        if (knownLocations.isEmpty()) {
            if (Emmatone.settings().exploreForBlocks.value && !calcFailed) {
                return new PathingCommand(new GoalRunAway(1, start) {
                    @Override
                    public boolean isInGoal(int x, int y, int z) {
                        return false;
                    }

                    @Override
                    public double heuristic() {
                        return Double.NEGATIVE_INFINITY;
                    }
                }, PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH);
            }
            logDirect("No known locations of " + gettingTo + ", canceling GetToBlock");
            if (isSafeToCancel) {
                onLostControl();
            }
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        Goal goal = new GoalComposite(knownLocations.stream().map(this::createGoal).toArray(Goal[]::new));
        if (calcFailed) {
            if (Emmatone.settings().blacklistClosestOnFailure.value) {
                logDirect("Unable to find any path to " + gettingTo + ", blacklisting presumably unreachable closest instances...");
                blacklistClosest();
                return onTick(false, isSafeToCancel); // gamer moment
            } else {
                logDirect("Unable to find any path to " + gettingTo + ", canceling GetToBlock");
                if (isSafeToCancel) {
                    onLostControl();
                }
                return new PathingCommand(goal, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
        }
        int mineGoalUpdateInterval = Emmatone.settings().mineGoalUpdateInterval.value;
        if (mineGoalUpdateInterval != 0 && tickCount++ % mineGoalUpdateInterval == 0) { // big brain
            List<BlockPos> current = new ArrayList<>(knownLocations);
            CalculationContext context = new GetToBlockCalculationContext(true);
            Emmatone.getExecutor().execute(() -> rescan(current, context));
        }
        if (goal.isInGoal(ctx.playerFeet()) && goal.isInGoal(emmatone.getPathingBehavior().pathStart()) && isSafeToCancel) {
            // we're there
            if (rightClickOnArrival(gettingTo.getBlock())) {
                if (rightClick()) {
                    onLostControl();
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
            } else {
                onLostControl();
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
        }
        return new PathingCommand(goal, PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    // blacklist the closest block and its adjacent blocks
    public synchronized boolean blacklistClosest() {
        List<BlockPos> newBlacklist = new ArrayList<>();
        knownLocations.stream().min(Comparator.comparingDouble(ctx.playerFeet()::distSqr)).ifPresent(newBlacklist::add);
        outer:
        while (true) {
            for (BlockPos known : knownLocations) {
                for (BlockPos blacklist : newBlacklist) {
                    if (areAdjacent(known, blacklist)) { // directly adjacent
                        newBlacklist.add(known);
                        knownLocations.remove(known);
                        continue outer;
                    }
                }
            }
            // i can't do break; (codacy gets mad), and i can't do if(true){break}; (codacy gets mad)
            // so i will do this
            switch (newBlacklist.size()) {
                default:
                    break outer;
            }
        }
        logDebug("Blacklisting unreachable locations " + newBlacklist);
        blacklist.addAll(newBlacklist);
        return !newBlacklist.isEmpty();
    }

    // this is to signal to MineProcess that we don't care about the allowBreak setting
    // it is NOT to be used to actually calculate a path
    public class GetToBlockCalculationContext extends CalculationContext {

        public GetToBlockCalculationContext(boolean forUseOnAnotherThread) {
            super(GetToBlockProcess.super.emmatone, forUseOnAnotherThread);
        }

        @Override
        public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
            return 1;
        }
    }

    // safer than direct double comparison from distanceSq
    private boolean areAdjacent(BlockPos posA, BlockPos posB) {
        int diffX = Math.abs(posA.getX() - posB.getX());
        int diffY = Math.abs(posA.getY() - posB.getY());
        int diffZ = Math.abs(posA.getZ() - posB.getZ());
        return (diffX + diffY + diffZ) == 1;
    }

    @Override
    public synchronized void onLostControl() {
        gettingTo = null;
        knownLocations = null;
        start = null;
        blacklist = null;
        if (ctx.player() != null) {
            ctx.player().setShiftKeyDown(false);
        }
    }

    @Override
    public String displayName0() {
        if (knownLocations.isEmpty()) {
            return "Exploring randomly to find " + gettingTo + ", no known locations";
        }
        return "Get To " + gettingTo + ", " + knownLocations.size() + " known locations";
    }

    private synchronized void rescan(List<BlockPos> known, CalculationContext context) {
        List<BlockPos> positions = MineProcess.searchWorld(context, new BlockOptionalMetaLookup(gettingTo), 64, known, blacklist, Collections.emptyList());
        positions.removeIf(blacklist::contains);
        knownLocations = positions;
    }

    private Goal createGoal(BlockPos pos) {
        if (walkIntoInsteadOfAdjacent(gettingTo.getBlock())) {
            return new GoalTwoBlocks(pos);
        }
        if (blockOnTopMustBeRemoved(gettingTo.getBlock()) && MovementHelper.isBlockNormalCube(emmatone.bsi.get0(pos.above()))) { // TODO this should be the check for chest openability
            return new GoalBlock(pos.above());
        }
        return new GoalGetToBlock(pos);
    }

    private boolean rightClick() {
        // If a container menu is already open, we're done
        if (!(ctx.player().containerMenu instanceof InventoryMenu)) {
            return true;
        }
        for (BlockPos pos : knownLocations) {
            Optional<Rotation> reachable = RotationUtils.reachable(ctx, pos, ctx.playerController().getBlockReachDistance());
            if (reachable.isPresent()) {
                emmatone.getLookBehavior().updateTarget(reachable.get(), true);
                if (knownLocations.contains(ctx.getSelectedBlock().orElse(null))) {
                    {
                        Direction face = ctx.objectMouseOver() instanceof BlockHitResult bhr ? bhr.getDirection() : Direction.UP;
                        BlockInteraction.placeBlock(ctx, pos, face);
                    }
                    if (!(ctx.player().containerMenu instanceof InventoryMenu)) {
                        return true;
                    }
                }
                if (arrivalTickCount++ > 20) {
                    logDirect("Right click timed out");
                    return true;
                }
                return false; // trying to right click, will do it next tick or so
            }
        }
        logDirect("Arrived but failed to right click open");
        return true;
    }

    private boolean walkIntoInsteadOfAdjacent(Block block) {
        if (!Emmatone.settings().enterPortal.value) {
            return false;
        }
        return block == Blocks.NETHER_PORTAL;
    }

    private boolean rightClickOnArrival(Block block) {
        if (!Emmatone.settings().rightClickContainerOnArrival.value) {
            return false;
        }
        return block == Blocks.CRAFTING_TABLE || block == Blocks.FURNACE || block == Blocks.BLAST_FURNACE || block == Blocks.ENDER_CHEST || block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST;
    }

    private boolean blockOnTopMustBeRemoved(Block block) {
        if (!rightClickOnArrival(block)) { // only if we plan to actually open it on arrival
            return false;
        }
        // only these chests; you can open a crafting table or furnace even with a block on top
        return block == Blocks.ENDER_CHEST || block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST;
    }
}
