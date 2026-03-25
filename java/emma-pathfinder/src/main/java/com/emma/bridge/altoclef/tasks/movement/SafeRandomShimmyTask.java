package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.control.BlockInteraction;
import adris.altoclef.control.DirectInput;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * Will move around randomly while holding shift
 * Used to escape weird situations where baritone doesn't work.
 */
public class SafeRandomShimmyTask extends Task {

    private final TimerGame _lookTimer;
    private boolean wasBreaking = false;

    public SafeRandomShimmyTask(float randomLookInterval) {
        _lookTimer = new TimerGame(randomLookInterval);
    }

    public SafeRandomShimmyTask() {
        this(5);
    }

    @Override
    protected void onStart() {
        _lookTimer.reset();
    }

    @Override
    protected Task onTick() {

        if (_lookTimer.elapsed()) {
            Debug.logMessage("Random Orientation");
            _lookTimer.reset();
            LookHelper.randomOrientation();
        }

        DirectInput.setSneaking(true);
        DirectInput.setForward(true);

        // Raycast to find block in front of the player (replaces crosshairTarget read)
        AltoClef mod = AltoClef.getInstance();
        Vec3d start = mod.getPlayer().getCameraPosVec(1.0F);
        Vec3d look = mod.getPlayer().getRotationVec(1.0F);
        double reach = mod.getClientBaritone().getPlayerContext()
                .playerController().getBlockReachDistance();
        Vec3d end = start.add(look.multiply(reach));
        BlockHitResult hit = mod.getWorld().raycast(
                new RaycastContext(start, end, RaycastContext.ShapeType.COLLIDER,
                        RaycastContext.FluidHandling.NONE, mod.getPlayer()));
        if (hit.getType() == HitResult.Type.BLOCK) {
            if (!wasBreaking) {
                BlockInteraction.startBreaking(mod, hit.getBlockPos(), hit.getSide());
                wasBreaking = true;
            } else {
                BlockInteraction.continueBreaking(mod, hit.getBlockPos(), hit.getSide());
            }
        }
        return null;
    }

    @Override
    protected void onStop(Task interruptTask) {
        DirectInput.setForward(false);
        DirectInput.setSneaking(false);
        MinecraftClient.getInstance().interactionManager.cancelBlockBreaking();
        wasBreaking = false;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof SafeRandomShimmyTask;
    }

    @Override
    protected String toDebugString() {
        return "Shimmying";
    }
}
