package adris.altoclef.tasks.entity;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.ProjectileHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import baritone.api.utils.Rotation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.thrown.PotionEntity;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;

/**
 * Throws a splash potion at a target entity.
 * Modeled on ThrowEnderPearlSimpleProjectileTask but targeting entities
 * with splash potion physics (slower velocity, higher gravity arc).
 */
public class ThrowSplashPotionTask extends Task {

    private final TimerGame thrownTimer = new TimerGame(3);
    private final Entity target;
    private boolean thrown = false;

    public ThrowSplashPotionTask(Entity target) {
        this.target = target;
    }

    private static boolean cleanThrow(AltoClef mod, float yaw, float pitch) {
        Rotation rotation = new Rotation(yaw, -1 * pitch);
        float range = 3f;
        Vec3d delta = LookHelper.toVec3d(rotation).multiply(range);
        Vec3d start = LookHelper.getCameraPos(mod);
        return LookHelper.cleanLineOfSight(start.add(delta), range);
    }

    private static Rotation calculateThrowLook(AltoClef mod, Entity target) {
        Vec3d start = ProjectileHelper.getThrowOrigin(mod.getPlayer());
        // Lead prediction — potions are slow, need more lead than melee
        Vec3d targetPos = ProjectileHelper.getLeadPredictedAimPos(start, target, 3.0f);

        double gravity = ProjectileHelper.SPLASH_POTION_GRAVITY_ACCEL;
        double speed = ProjectileHelper.SPLASH_POTION_SPEED;

        float yaw = LookHelper.getLookRotation(mod, targetPos).getYaw();
        double flatDistance = WorldHelper.distanceXZ(start, targetPos);
        double heightDiff = start.y - targetPos.y;

        double[] pitches = ProjectileHelper.calculateAnglesForSimpleProjectileMotion(
            heightDiff, flatDistance, speed, gravity);

        // Prefer lower arc; fall back to high arc if obstructed
        double pitch = cleanThrow(mod, yaw, (float) pitches[0]) ? pitches[0] : pitches[1];
        return new Rotation(yaw, -1 * (float) pitch);
    }

    @Override
    protected void onStart() {
        thrownTimer.forceElapse();
        thrown = false;
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();

        // Don't double-throw while a potion is in flight
        if (mod.getEntityTracker().entityFound(PotionEntity.class)) {
            thrownTimer.reset();
        }

        if (thrownTimer.elapsed()) {
            if (mod.getSlotHandler().forceEquipItem(Items.SPLASH_POTION)) {
                Rotation lookTarget = calculateThrowLook(mod, target);
                LookHelper.lookAt(lookTarget);
                if (LookHelper.isLookingAt(mod, lookTarget)) {
                    MinecraftClient.getInstance().interactionManager
                        .interactItem(mod.getPlayer(), Hand.MAIN_HAND);
                    thrown = true;
                    thrownTimer.reset();
                }
            }
        }
        return null;
    }

    @Override
    protected void onStop(Task interruptTask) {
    }

    @Override
    public boolean isFinished() {
        return (thrown && thrownTimer.elapsed())
            || (!thrown && !AltoClef.getInstance().getItemStorage().hasItem(Items.SPLASH_POTION));
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof ThrowSplashPotionTask task) {
            return task.target == this.target;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Throwing splash potion at " + target.getType().getTranslationKey();
    }
}
