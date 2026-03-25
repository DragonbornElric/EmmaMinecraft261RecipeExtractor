package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.GetCloseToBlockTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.control.BlockInteraction;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.world.RaycastContext;

import java.util.HashSet;
import java.util.function.Predicate;

public class CollectBucketLiquidTask extends ResourceTask {

    private final HashSet<BlockPos> blacklist = new HashSet<>();
    private final int count;

    private final Item target;
    private final Block toCollect;
    private final String liquidName;
    private final MovementProgressChecker progressChecker = new MovementProgressChecker();

    private boolean wasWandering = false;

    public CollectBucketLiquidTask(String liquidName, Item filledBucket, int targetCount, Block toCollect) {
        super(filledBucket, targetCount);
        this.liquidName = liquidName;
        target = filledBucket;
        count = targetCount;
        this.toCollect = toCollect;
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @SuppressWarnings("ConstantConditions")
    @Override
    protected void onResourceStart(AltoClef mod) {
        // Track fluids
        mod.getBehaviour().push();
        mod.getBehaviour().setRayTracingFluidHandling(RaycastContext.FluidHandling.SOURCE_ONLY);

        // Avoid breaking / placing blocks at our liquid
        mod.getBehaviour().avoidBlockBreaking((pos) -> MinecraftClient.getInstance().world.getBlockState(pos).getBlock() == toCollect);
        mod.getBehaviour().avoidBlockPlacing((pos) -> MinecraftClient.getInstance().world.getBlockState(pos).getBlock() == toCollect);

        mod.getClientBaritoneSettings().avoidUpdatingFallingBlocks.value = true;
        //_blacklist.clear();

        progressChecker.reset();
    }


    @Override
    protected Task onTick() {
        Task result = super.onTick();
        // Reset our "first time" timeout/wander flag.
        if (!thisOrChildAreTimedOut()) {
            wasWandering = false;
        }
        return result;
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
            progressChecker.reset();
        }

        // Get buckets if we need em
        int bucketsNeeded = count - mod.getItemStorage().getItemCount(Items.BUCKET) - mod.getItemStorage().getItemCount(target);
        if (bucketsNeeded > 0) {
            setDebugState("Getting bucket...");
            return TaskCatalogue.getItemTask(Items.BUCKET, bucketsNeeded);
        }

        Predicate<BlockPos> isSafeSourceLiquid = blockPos -> {
            if (blacklist.contains(blockPos)) return false;
            // Don't target the block the player is currently standing in — server will reject
            // a bucket interaction on a block the player is physically inside.
            if (blockPos.equals(mod.getPlayer().getBlockPos())) return false;
            if (!WorldHelper.canReach(blockPos)) return false;
            if (!WorldHelper.canReach(blockPos.up())) return false; // We may try reaching the block above.
            assert MinecraftClient.getInstance().world != null;

            Block above = mod.getWorld().getBlockState(blockPos.up()).getBlock();
            // We break the block above. If it's bedrock, ignore.
            // Also skip if same liquid is above (not a surface block we can interact with).
            if (above == Blocks.BEDROCK || above == toCollect) {
                return false;
            }

            // Anti-spill check: only for lava without fire resistance — water spilling is harmless
            if (toCollect == Blocks.LAVA && !mod.getPlayer().hasStatusEffect(StatusEffects.FIRE_RESISTANCE)) {
                for (Direction direction : Direction.values()) {
                    if (direction.getAxis().isVertical()) continue;
                    if (mod.getWorld().getBlockState(blockPos.up().offset(direction)).getBlock() == toCollect) {
                        return false;
                    }
                }
            }

            return WorldHelper.isSourceBlock(blockPos, false);
        };

        // Find nearest water and right click it
        if (mod.getBlockScanner().anyFound(isSafeSourceLiquid, toCollect)) {
            // We want to MINIMIZE this distance to liquid.
            setDebugState("Trying to collect...");
            //Debug.logMessage("TEST: " + RayTraceUtils.fluidHandling);

            return new DoToClosestBlockTask(blockPos -> {
                // Clear above if lava because we can't enter.
                // but NOT if we're standing right above.
                if (mod.getWorld().getBlockState(blockPos.up()).isSolid()) {
                    if (!progressChecker.check(mod)) {
                        mod.getClientBaritone().getPathingBehavior().cancelEverything();
                        mod.getClientBaritone().getPathingBehavior().forceCancel();
                        mod.getClientBaritone().getExploreProcess().onLostControl();
                        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
                        Debug.logMessage("Failed to break, blacklisting.");
                        mod.getBlockScanner().requestBlockUnreachable(blockPos);
                        blacklist.add(blockPos);
                    }
                    return new DestroyBlockTask(blockPos.up());
                }

                if (tries > 75) {
                    if (timeoutTimer.elapsed()) {
                        tries = 0;
                    }
                    mod.log("trying to wander "+timeoutTimer.getDuration());
                    return new TimeoutWanderTask();
                }
                timeoutTimer.reset();

                // We can reach the block.
                if (BlockInteraction.isInReach(mod, blockPos) &&
                        mod.getClientBaritone().getPathingBehavior().isSafeToCancel()) {
                    // Clear solid obstruction between player and fluid
                    BlockPos obstruction = BlockInteraction.fluidLOSObstruction(mod, blockPos);
                    if (obstruction != null) {
                        return new DestroyBlockTask(obstruction);
                    }
                    if (collectCooldown.elapsed()) {
                        if (BlockInteraction.tryCollectFluid(mod, blockPos)) {
                            collectCooldown.reset(); // dispatched — wait 150ms before next attempt
                        } else {
                            tries++; // head still turning toward fluid
                        }
                    }
                    return null;
                }
                // Get close enough.
                // up because if we go below we'll try to move next to the liquid (for lava, not a good move)
                if (this.thisOrChildAreTimedOut() && !wasWandering) {
                    mod.getBlockScanner().requestBlockUnreachable(blockPos.up());
                    wasWandering = true;
                }
                return new GetCloseToBlockTask(blockPos.up());
            }, isSafeSourceLiquid, toCollect);
        }

        // Dimension
        if (toCollect == Blocks.WATER && WorldHelper.getCurrentDimension() == Dimension.NETHER) {
            return new DefaultGoToDimensionTask(Dimension.OVERWORLD);
        }

        // Oof, no liquid found.
        setDebugState("Searching for liquid by wandering around aimlessly");

        return new TimeoutWanderTask();
    }
    int tries = 0;
    TimerGame timeoutTimer = new TimerGame(2);
    TimerGame collectCooldown = new TimerGame(0.15); // 150ms between dispatch attempts

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        mod.getBehaviour().pop();
        mod.getExtraBaritoneSettings().setInteractionPaused(false);

        mod.getClientBaritoneSettings().avoidUpdatingFallingBlocks.value = false;
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof CollectBucketLiquidTask task) {
            if (task.count != count) return false;
            return task.toCollect == toCollect;
        }
        return false;
    }

    @Override
    protected String toDebugStringName() {
        return "Collect " + count + " " + liquidName + " buckets";
    }

    public static class CollectWaterBucketTask extends CollectBucketLiquidTask {
        public CollectWaterBucketTask(int targetCount) {
            super("water", Items.WATER_BUCKET, targetCount, Blocks.WATER);
        }
    }

    public static class CollectLavaBucketTask extends CollectBucketLiquidTask {
        public CollectLavaBucketTask(int targetCount) {
            super("lava", Items.LAVA_BUCKET, targetCount, Blocks.LAVA);
        }
    }

}
