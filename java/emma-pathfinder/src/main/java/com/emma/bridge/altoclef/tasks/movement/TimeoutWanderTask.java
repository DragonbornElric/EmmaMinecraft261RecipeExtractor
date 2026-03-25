package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.entity.KillEntitiesTask;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.TaskHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import baritone.api.utils.input.Input;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * Call this when the place you're currently at is bad for some reason and you just wanna get away.
 */
public class TimeoutWanderTask extends Task implements ITaskRequiresGrounded {
    private final MovementProgressChecker stuckCheck = new MovementProgressChecker();
    private final float distanceToWander;
    private final MovementProgressChecker progressChecker = new MovementProgressChecker();
    private final boolean increaseRange;
    private Vec3d origin;
    private boolean _forceExplore;
    private Task _unstuckTask = null;
    private int failCounter;
    private double _wanderDistanceExtension;

    public TimeoutWanderTask(float distanceToWander, boolean increaseRange) {
        this.distanceToWander = distanceToWander;
        this.increaseRange = increaseRange;
        _forceExplore = false;
    }

    public TimeoutWanderTask(float distanceToWander) {
        this(distanceToWander, false);
    }

    public TimeoutWanderTask() {
        this(Float.POSITIVE_INFINITY, false);
    }

    public TimeoutWanderTask(boolean forceExplore) {
        this();
        _forceExplore = forceExplore;
    }

    public void resetWander() {
        _wanderDistanceExtension = 0;
    }

    @Override
    protected void onStart() {
        AltoClef mod = AltoClef.getInstance();
        mod.getClientBaritone().getPathingBehavior().forceCancel();
        origin = mod.getPlayer().getPos();
        progressChecker.reset();
        stuckCheck.reset();
        failCounter = 0;
        TaskHelper.cleanupCursorSlot(mod);
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();

        if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
            progressChecker.reset();
        }

        if (TaskHelper.handleNetherPortalEscape(mod)) {
            setDebugState("Getting out from nether portal");
            return null;
        }

        if (_unstuckTask != null && _unstuckTask.isActive() && !_unstuckTask.isFinished() && TaskHelper.stuckInBlock(mod) != null) {
            setDebugState("Getting unstuck from block.");
            stuckCheck.reset();
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
            mod.getClientBaritone().getExploreProcess().onLostControl();
            return _unstuckTask;
        }

        if (!progressChecker.check(mod) || !stuckCheck.check(mod)) {
            List<Entity> closeEntities = mod.getEntityTracker().getCloseEntities();
            for (Entity entity : closeEntities) {
                if (entity instanceof MobEntity && entity.getPos().isInRange(mod.getPlayer().getPos(), 1)) {
                    setDebugState("Killing annoying entity.");
                    return new KillEntitiesTask(entity.getClass());
                }
            }
            BlockPos blockStuck = TaskHelper.stuckInBlock(mod);
            if (blockStuck != null) {
                failCounter++;
                _unstuckTask = TaskHelper.getUnstuckTask();
                return _unstuckTask;
            }
            stuckCheck.reset();
        }

        setDebugState("Exploring.");
        if (!mod.getClientBaritone().getExploreProcess().isActive()) {
            mod.getClientBaritone().getExploreProcess().explore((int) origin.getX(), (int) origin.getZ());
        }
        if (!progressChecker.check(mod)) {
            progressChecker.reset();
            if (!_forceExplore) {
                failCounter++;
                Debug.logMessage("Failed exploring.");
            }
        }
        return null;
    }

    @Override
    protected void onStop(Task interruptTask) {
        AltoClef.getInstance().getClientBaritone().getPathingBehavior().forceCancel();
        if (isFinished() && increaseRange) {
            _wanderDistanceExtension += distanceToWander;
            Debug.logMessage("Increased wander range");
        }
    }

    @Override
    public boolean isFinished() {
        if (Float.isInfinite(distanceToWander)) return false;
        if (failCounter > 10) return true;

        ClientPlayerEntity player = AltoClef.getInstance().getPlayer();
        if (player != null && player.getPos() != null && (player.isOnGround() || player.isTouchingWater())) {
            double sqDist = player.getPos().squaredDistanceTo(origin);
            double toWander = distanceToWander + _wanderDistanceExtension;
            return sqDist > toWander * toWander;
        }
        return false;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof TimeoutWanderTask task) {
            if (Float.isInfinite(task.distanceToWander) || Float.isInfinite(distanceToWander)) {
                return Float.isInfinite(task.distanceToWander) == Float.isInfinite(distanceToWander);
            }
            return Math.abs(task.distanceToWander - distanceToWander) < 0.5f;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Wander for " + (distanceToWander + _wanderDistanceExtension) + " blocks";
    }
}
