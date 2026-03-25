package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.TaskHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import baritone.api.pathing.goals.Goal;
import net.minecraft.util.math.BlockPos;

/**
 * Turns a baritone goal into a task.
 */
public abstract class CustomBaritoneGoalTask extends Task implements ITaskRequiresGrounded {
    private final Task wanderTask = new TimeoutWanderTask(5, true);
    private final MovementProgressChecker stuckCheck = new MovementProgressChecker();
    private final boolean wander;
    protected MovementProgressChecker checker = new MovementProgressChecker();
    protected Goal cachedGoal = null;
    private Task unstuckTask = null;

    public CustomBaritoneGoalTask(boolean wander) {
        this.wander = wander;
    }

    public CustomBaritoneGoalTask() {
        this(true);
    }

    @Override
    protected void onStart() {
        AltoClef.getInstance().getClientBaritone().getPathingBehavior().forceCancel();
        checker.reset();
        stuckCheck.reset();
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();

        if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
            checker.reset();
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
        if (!checker.check(mod) || !stuckCheck.check(mod)) {
            BlockPos blockStuck = TaskHelper.stuckInBlock(mod);
            if (blockStuck != null) {
                unstuckTask = TaskHelper.getUnstuckTask();
                return unstuckTask;
            }
            stuckCheck.reset();
        }
        if (cachedGoal == null) {
            cachedGoal = newGoal(mod);
        }

        if (wander) {
            if (isFinished()) {
                // Don't wander if we've reached our goal.
                checker.reset();
            } else {
                if (wanderTask.isActive() && !wanderTask.isFinished()) {
                    setDebugState("Wandering...");
                    checker.reset();
                    return wanderTask;
                }
                if (!checker.check(mod)) {
                    Debug.logMessage("Failed to make progress on goal, wandering.");
                    onWander(mod);
                    return wanderTask;
                }
            }
        }
        if (!mod.getClientBaritone().getCustomGoalProcess().isActive()
                && mod.getClientBaritone().getPathingBehavior().isSafeToCancel()) {
            mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(cachedGoal);
        }
        setDebugState("Completing goal.");
        return null;
    }

    @Override
    public boolean isFinished() {
        if (cachedGoal == null) {
            cachedGoal = newGoal(AltoClef.getInstance());
        }
        return cachedGoal != null && cachedGoal.isInGoal(AltoClef.getInstance().getPlayer().getBlockPos());
    }

    @Override
    protected void onStop(Task interruptTask) {
        AltoClef.getInstance().getClientBaritone().getPathingBehavior().forceCancel();
    }

    protected abstract Goal newGoal(AltoClef mod);

    protected void onWander(AltoClef mod) {
    }
}
