package adris.altoclef.tasks.construction;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.Subscription;
import adris.altoclef.eventbus.events.BlockPlaceEvent;
import adris.altoclef.multiversion.blockpos.BlockPosVer;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.control.BlockInteraction;
import adris.altoclef.control.DirectInput;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.time.TimerGame;
import baritone.api.utils.IPlayerContext;
import baritone.pathing.movement.MovementHelper;
import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.apache.commons.lang3.ArrayUtils;

import java.util.Arrays;
import java.util.function.Predicate;

/**
 * Place a type of block nearby, anywhere.
 * <p>
 * Also known as the "bear strats" task.
 */
public class PlaceBlockNearbyTask extends Task {

    private final Block[] toPlace;

    private final MovementProgressChecker progressChecker = new MovementProgressChecker();
    private final TimeoutWanderTask wander = new TimeoutWanderTask(5);

    private final TimerGame _randomlookTimer = new TimerGame(0.25);
    private final Predicate<BlockPos> _canPlaceHere;
    private BlockPos justPlaced; // Where we JUST placed a block.
    private BlockPos tryPlace;   // Where we should TRY placing a block.
    // Oof, necesarry for the onBlockPlaced action.
    private Subscription<BlockPlaceEvent> _onBlockPlaced;

    public PlaceBlockNearbyTask(Predicate<BlockPos> canPlaceHere, Block... toPlace) {
        this.toPlace = toPlace;
        _canPlaceHere = canPlaceHere;
    }

    public PlaceBlockNearbyTask(Block... toPlace) {
        this(blockPos -> true, toPlace);
    }

    @Override
    protected void onStart() {
        progressChecker.reset();
        // Check for blocks being placed
        _onBlockPlaced = EventBus.subscribe(BlockPlaceEvent.class, evt -> {
            if (ArrayUtils.contains(toPlace, evt.blockState.getBlock())) {
                stopPlacing();
            }
        });
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();

        if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
            progressChecker.reset();
        }
        // Method:
        // - If looking at placable block
        //      Place immediately
        // Find a spot to place
        // - Prefer flat areas (open space, block below) closest to player
        // -

        // Close screen first
        ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();
        if (!cursorStack.isEmpty()) {
           /* Optional<Slot> moveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursorStack, false);
            if (moveTo.isPresent()) {
                mod.getSlotHandler().clickSlot(moveTo.get(), 0, SlotActionType.PICKUP);
                return null;
            }
            if (ItemHelper.canThrowAwayStack(mod, cursorStack)) {
                mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
                return null;
            }
            Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
            // Try throwing away cursor slot if it's garbage
            if (garbage.isPresent()) {
                mod.getSlotHandler().clickSlot(garbage.get(), 0, SlotActionType.PICKUP);
                return null;
            }
            mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);*/
        } else {
            StorageHelper.closeScreen();
        }

        // Try placing where we're looking right now.
        BlockPos current = getCurrentlyLookingBlockPlace(mod);
        if (current != null && _canPlaceHere.test(current)) {
            setDebugState("Placing since we can...");
            if (mod.getSlotHandler().forceEquipItem(ItemHelper.blocksToItems(toPlace))) {
                if (place(mod, current)) {
                    return null;
                }
            }
        }

        // Wander while we can.
        if (wander.isActive() && !wander.isFinished()) {
            setDebugState("Wandering, will try to place again later.");
            progressChecker.reset();
            return wander;
        }
        // Fail check
        if (!progressChecker.check(mod)) {
            Debug.logMessage("Failed placing, wandering and trying again.");
            LookHelper.randomOrientation();
            if (tryPlace != null) {
                mod.getBlockScanner().requestBlockUnreachable(tryPlace);
                tryPlace = null;
            }
            return wander;
        }

        // Try to place at a particular spot.
        if (tryPlace == null || !WorldHelper.canReach(tryPlace)) {
            tryPlace = locateClosePlacePos(mod);
        }
        if (tryPlace != null) {
            setDebugState("Trying to place at " + tryPlace);
            justPlaced = tryPlace;
            return new PlaceBlockTask(tryPlace, toPlace);
        }

        // Look in random places to maybe get a random hit
        if (_randomlookTimer.elapsed()) {
            _randomlookTimer.reset();
            LookHelper.randomOrientation();
        }

        setDebugState("Wandering until we randomly place or find a good place spot.");
        return new TimeoutWanderTask();
    }

    @Override
    protected void onStop(Task interruptTask) {
        stopPlacing();
        EventBus.unsubscribe(_onBlockPlaced);
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof PlaceBlockNearbyTask task) {
            return Arrays.equals(task.toPlace, toPlace);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Place " + Arrays.toString(toPlace) + " nearby";
    }

    @Override
    public boolean isFinished() {
        return justPlaced != null && ArrayUtils.contains(toPlace, AltoClef.getInstance().getWorld().getBlockState(justPlaced).getBlock());
    }

    public BlockPos getPlaced() {
        return justPlaced;
    }

    private BlockPos getCurrentlyLookingBlockPlace(AltoClef mod) {
        // Raycast from eye in look direction (replaces crosshairTarget read)
        Vec3d start = mod.getPlayer().getCameraPosVec(1.0F);
        Vec3d look = mod.getPlayer().getRotationVec(1.0F);
        double reach = mod.getClientBaritone().getPlayerContext()
                .playerController().getBlockReachDistance();
        Vec3d end = start.add(look.multiply(reach));
        BlockHitResult bhit = mod.getWorld().raycast(
                new RaycastContext(start, end, RaycastContext.ShapeType.OUTLINE,
                        RaycastContext.FluidHandling.NONE, mod.getPlayer()));
        if (bhit.getType() == HitResult.Type.BLOCK) {
            BlockPos bpos = bhit.getBlockPos();
            IPlayerContext ctx = mod.getClientBaritone().getPlayerContext();
            if (MovementHelper.canPlaceAgainst(ctx, bpos)) {
                BlockPos placePos = bhit.getBlockPos().add(bhit.getSide().getVector());
                // Don't place inside the player.
                if (WorldHelper.isInsidePlayer(placePos)) {
                    return null;
                }
                if (WorldHelper.canPlace(placePos)) {
                    return placePos;
                }
            }
        }
        return null;
    }

    private boolean blockEquipped() {
        return StorageHelper.isEquipped(ItemHelper.blocksToItems(toPlace));
    }

    private boolean place(AltoClef mod, BlockPos targetPlace) {
        if (!mod.getExtraBaritoneSettings().isInteractionPaused() && blockEquipped()) {
            // Shift click just for 100% container security.
            DirectInput.setSneaking(true);

            // Find an adjacent solid block to place against
            IPlayerContext ctx = mod.getClientBaritone().getPlayerContext();
            for (Direction dir : Direction.values()) {
                BlockPos support = targetPlace.offset(dir);
                if (MovementHelper.canPlaceAgainst(ctx, support) && BlockInteraction.isInReach(mod, support)) {
                    Direction face = dir.getOpposite(); // Face of support block pointing toward targetPlace
                    BlockInteraction.rightClickBlock(mod, support, face);
                    justPlaced = targetPlace;
                    Debug.logMessage("PRESSED");
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    private void stopPlacing() {
        DirectInput.setSneaking(false);
        AltoClef.getInstance().getClientBaritone().getBuilderProcess().onLostControl();
    }

    private BlockPos locateClosePlacePos(AltoClef mod) {
        int range = 7;
        BlockPos best = null;
        double smallestScore = Double.POSITIVE_INFINITY;
        BlockPos start = mod.getPlayer().getBlockPos().add(-range,-range,-range);
        BlockPos end = mod.getPlayer().getBlockPos().add(range,range,range);
        for (BlockPos blockPos : WorldHelper.scanRegion(start, end)) {
            boolean solid = WorldHelper.isSolidBlock(blockPos);
            boolean inside = WorldHelper.isInsidePlayer(blockPos);
            // We can't break this block.
            if (solid && !WorldHelper.canBreak(blockPos)) {
                continue;
            }
            // We can't place here as defined by user.
            if (!_canPlaceHere.test(blockPos)) {
                continue;
            }
            // We can't place here.
            if (!WorldHelper.canReach(blockPos) || !WorldHelper.canPlace(blockPos)) {
                continue;
            }
            boolean hasBelow = WorldHelper.isSolidBlock(blockPos.down());
            double distSq = BlockPosVer.getSquaredDistance(blockPos,mod.getPlayer().getPos());

            double score = distSq + (solid ? 4 : 0) + (hasBelow ? 0 : 10) + (inside ? 3 : 0);

            if (score < smallestScore) {
                best = blockPos;
                smallestScore = score;
            }
        }

        return best;
    }
}
