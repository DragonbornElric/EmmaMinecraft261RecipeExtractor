package adris.altoclef.tasks.construction;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.movement.RunAwayFromPositionTask;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.control.BlockInteraction;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.TaskHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import adris.altoclef.control.DirectInput;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import adris.altoclef.multiversion.versionedfields.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.PillagerEntity;
import net.minecraft.util.math.BlockPos;

/**
 * Destroy a block at a position.
 * <p>
 * Uses pure distance check + direct Mojang API calls for breaking,
 * matching the Phase 52-53 Baritone pattern (ProcessBreakHelper / Movement.executeBreak).
 * No raycast gating — server validates reach on the actual break call.
 */
public class DestroyBlockTask extends Task implements ITaskRequiresGrounded {
    private final MovementProgressChecker stuckCheck = new MovementProgressChecker();
    private final MovementProgressChecker moveChecker = new MovementProgressChecker();
    private final BlockPos pos;
    private static final int MAX_NAVIGATE_TICKS = 400; // 20 seconds at 20 TPS
    private Task unstuckTask = null;
    private boolean isMining;
    private boolean wasBreakingBlock = false;
    private int ticksNavigating = 0;

    public DestroyBlockTask(BlockPos pos) {
        this.pos = pos;
    }

    @Override
    protected void onStart() {
        AltoClef mod = AltoClef.getInstance();
        mod.getClientBaritone().getPathingBehavior().forceCancel();
        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
        mod.getClientBaritone().getBuilderProcess().onLostControl();
        moveChecker.reset();
        stuckCheck.reset();
        ticksNavigating = 0;
        TaskHelper.cleanupCursorSlot(mod);
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();

        // Blacklist pillager wool to avoid aggro
        if (mod.getWorld().getBlockState(pos).getBlock() == Blocks.WHITE_WOOL) {
            for (Entity entity : mod.getWorld().getEntities()) {
                if (entity instanceof PillagerEntity && pos.isWithinDistance(entity.getPos(), 144)) {
                    Debug.logMessage("Blacklisting pillager wool.");
                    mod.getBlockScanner().requestBlockUnreachable(pos, 0);
                }
            }
        }

        if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
            moveChecker.reset();
        }

        if (TaskHelper.handleNetherPortalEscape(mod)) {
            setDebugState("Getting out from nether portal");
            return null;
        }

        if (unstuckTask != null && unstuckTask.isActive() && !unstuckTask.isFinished() && TaskHelper.stuckInBlock(mod) != null) {
            setDebugState("Getting unstuck from block.");
            stuckCheck.reset();
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
            mod.getClientBaritone().getExploreProcess().onLostControl();
            return unstuckTask;
        }

        // Distance-based reach check (no raycast — matches Movement.prepared() pattern)
        Vec3d eye = mod.getPlayer().getEyePos();
        double dist = eye.distanceTo(Vec3d.ofCenter(pos));
        double reachDist = mod.getClientBaritone().getPlayerContext()
                .playerController().getBlockReachDistance();
        boolean inRange = dist <= reachDist;

        // Navigation progress checks only apply while the block is not yet in reach.
        // When in range, standing still to mine is correct — not "stuck".
        // Cache check() results: it calls setProgress() internally (not idempotent).
        if (inRange) {
            moveChecker.reset();
            stuckCheck.reset();
            ticksNavigating = 0;
        } else {
            ticksNavigating++;
            if (ticksNavigating > MAX_NAVIGATE_TICKS) {
                Debug.logMessage("DestroyBlockTask: Navigation timeout (" + MAX_NAVIGATE_TICKS
                        + " ticks) for " + pos.toShortString() + " — marking unreachable");
                mod.getBlockScanner().requestBlockUnreachable(pos, 0);
                mod.getClientBaritone().getPathingBehavior().forceCancel();
                ticksNavigating = 0;
            }
            boolean moveOk = moveChecker.check(mod);
            boolean stuckOk = stuckCheck.check(mod);
            if (!moveOk || !stuckOk) {
                BlockPos blockStuck = TaskHelper.stuckInBlock(mod);
                if (blockStuck != null) {
                    unstuckTask = TaskHelper.getUnstuckTask();
                    return unstuckTask;
                }
                stuckCheck.reset();
            }
            if (!moveOk) {
                moveChecker.reset();
                mod.getBlockScanner().requestBlockUnreachable(pos);
            }
        }

        // Avoid breaking block directly below us if dangerous
        if (!WorldHelper.isSolidBlock(pos.up()) && mod.getPlayer().getPos().y > pos.getY()
                && pos.isWithinDistance(mod.getPlayer().isOnGround() ? mod.getPlayer().getPos() : mod.getPlayer().getPos().add(0, -1, 0), 0.89)) {
            if (WorldHelper.dangerousToBreakIfRightAbove(pos)) {
                setDebugState("It's dangerous to break as we're right above it, moving away and trying again.");
                return new RunAwayFromPositionTask(3, pos.getY(), pos);
            }
        }

        if (inRange && (mod.getPlayer().isTouchingWater() || mod.getPlayer().isOnGround())
                && !mod.getFoodChain().needsToEat() && !WorldHelper.isInNetherPortal()) {
            setDebugState("Block in range, mining...");
            stuckCheck.reset();
            isMining = true;
            DirectInput.setSneaking(false);
            DirectInput.setBack(false);
            DirectInput.setForward(false);
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
            mod.getClientBaritone().getBuilderProcess().onLostControl();
            // Cosmetic look-at (not a gate)
            LookHelper.lookAt(mod, pos);
            // Compute face from eye position (no raycast)
            Direction face = BlockInteraction.computeFace(mod, pos);
            // Tool equip is handled in PlayerInteractionFixChain
            if (!wasBreakingBlock) {
                BlockInteraction.startBreaking(mod, pos, face);
                wasBreakingBlock = true;
            } else {
                BlockInteraction.continueBreaking(mod, pos, face);
            }
        } else {
            setDebugState("Getting to block...");
            if (isMining && mod.getPlayer().isTouchingWater()) {
                setDebugState("We are in water... holding break button");
                isMining = false;
                Direction face = BlockInteraction.computeFace(mod, pos);
                if (!wasBreakingBlock) {
                    BlockInteraction.startBreaking(mod, pos, face);
                    wasBreakingBlock = true;
                } else {
                    BlockInteraction.continueBreaking(mod, pos, face);
                }
            } else {
                isMining = false;
            }
            if (pos.isWithinDistance(mod.getPlayer().getPos(), 2)) {
                if (!mod.getClientBaritone().getPathingBehavior().isPathing() && !mod.getPlayer().isTouchingWater()
                        && !mod.getFoodChain().needsToEat()) {
                    DirectInput.setBack(true);
                    DirectInput.setSneaking(true);
                } else {
                    DirectInput.setBack(false);
                    DirectInput.setSneaking(false);
                }
            }
            if (!mod.getClientBaritone().getCustomGoalProcess().isActive()) {
                mod.getClientBaritone().getBuilderProcess().onLostControl();
                mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(
                        mod.getWorld().getBlockState(pos.up()).getBlock() == Blocks.SNOW
                                ? new GoalBlock(pos) : new GoalNear(pos, 1));
            }
        }
        return null;
    }

    @Override
    protected void onStop(Task interruptTask) {
        AltoClef mod = AltoClef.getInstance();
        mod.getClientBaritone().getPathingBehavior().forceCancel();
        if (!AltoClef.inGame()) return;
        MinecraftClient.getInstance().interactionManager.cancelBlockBreaking();
        wasBreakingBlock = false;
        ticksNavigating = 0;
        DirectInput.setSneaking(false);
        DirectInput.setBack(false);
        DirectInput.setForward(false);
    }

    @Override
    public boolean isFinished() {
        return AltoClef.getInstance().getWorld().getBlockState(pos).isAir();
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof DestroyBlockTask task && task.pos.equals(pos);
    }

    @Override
    protected String toDebugString() {
        return "Destroy block at " + pos.toShortString();
    }

}
