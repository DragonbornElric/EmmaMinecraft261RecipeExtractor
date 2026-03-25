package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.construction.PutOutFireTask;
import adris.altoclef.tasks.movement.EnterNetherPortalTask;
import adris.altoclef.tasks.movement.EscapeFromLavaTask;
import adris.altoclef.tasks.movement.GetCloseToBlockTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.SafeRandomShimmyTask;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.control.BlockInteraction;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import adris.altoclef.control.DirectInput;
import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class WorldSurvivalChain extends SingleTaskChain {

    private final TimerGame wasInLavaTimer = new TimerGame(1);
    private final TimerGame portalStuckTimer = new TimerGame(5);
    private boolean wasAvoidingDrowning;

    private BlockPos _extinguishWaterPosition;

    public WorldSurvivalChain(TaskRunner runner) {
        super(runner);
    }

    @Override
    protected void onTaskFinish(AltoClef mod) {

    }

    @Override
    public float getPriority() {
        if (!AltoClef.inGame()) return Float.NEGATIVE_INFINITY;

        AltoClef mod = AltoClef.getInstance();

        // Drowning
        handleDrowning(mod);

        // Lava Escape
        if (isInLavaOhShit(mod) && mod.getBehaviour().shouldEscapeLava()) {
            setTask(new EscapeFromLavaTask(mod));
            return 100;
        }

        // Proactive lava avoidance — detect lava in movement path before stepping in.
        // Safety net for any movement system (Baritone, flee tasks, direct input).
        if (isAboutToWalkIntoLava(mod) && mod.getBehaviour().shouldEscapeLava()) {
            setTask(new SafeRandomShimmyTask());
            return 95;
        }

        // Fire escape
        if (isInFire(mod)) {
            setTask(new DoToClosestBlockTask(PutOutFireTask::new, Blocks.FIRE, Blocks.SOUL_FIRE));
            return 100;
        }

        // Extinguish with water
        if (mod.getModSettings().shouldExtinguishSelfWithWater()) {
            if (!(mainTask instanceof EscapeFromLavaTask && isCurrentlyRunning(mod)) && mod.getPlayer().isOnFire() && !mod.getPlayer().hasStatusEffect(StatusEffects.FIRE_RESISTANCE) && !mod.getWorld().getDimension().ultrawarm()) {
                // Extinguish ourselves
                if (mod.getItemStorage().hasItem(Items.WATER_BUCKET)) {
                    BlockPos targetWaterPos = mod.getPlayer().getBlockPos();
                    if (WorldHelper.isSolidBlock(targetWaterPos.down()) && WorldHelper.canPlace(targetWaterPos)) {
                        if (BlockInteraction.isInReach(mod, targetWaterPos.down())) {
                            if (mod.getSlotHandler().forceEquipItem(Items.WATER_BUCKET)) {
                                LookHelper.lookAt(mod, targetWaterPos.down()); // cosmetic
                                _extinguishWaterPosition = targetWaterPos;
                                BlockInteraction.rightClickBlock(mod, targetWaterPos.down(), Direction.UP);
                                setTask(null);
                                return 90;
                            }
                        }
                    }
                }
                setTask(new DoToClosestBlockTask(GetToBlockTask::new, Blocks.WATER));
                return 90;
            } else if (mod.getItemStorage().hasItem(Items.BUCKET) && _extinguishWaterPosition != null && mod.getBlockScanner().isBlockAtPosition(_extinguishWaterPosition, Blocks.WATER)) {
                // Pick up the water
                if (BlockInteraction.isInReach(mod, _extinguishWaterPosition)) {
                    setTask(null);
                    BlockInteraction.tryCollectFluid(mod, _extinguishWaterPosition);
                } else {
                    setTask(new GetCloseToBlockTask(_extinguishWaterPosition));
                }
                return 60;
            } else {
                _extinguishWaterPosition = null;
            }
        }

        // Portal stuck
        if (isStuckInNetherPortal()) {
            // We can't break or place while inside a portal (not really)
            mod.getExtraBaritoneSettings().setInteractionPaused(true);
        } else {
            // We're no longer stuck, but we might want to move AWAY from our stuck position.
            portalStuckTimer.reset();
            mod.getExtraBaritoneSettings().setInteractionPaused(false);
        }
        if (portalStuckTimer.elapsed()) {
            // We're stuck inside a portal, so get out.
            // Don't allow breaking while we're inside the portal.
            setTask(new SafeRandomShimmyTask());
            return 60;
        }

        return Float.NEGATIVE_INFINITY;
    }

    private void handleDrowning(AltoClef mod) {
        // Swim
        boolean avoidedDrowning = false;
        if (mod.getModSettings().shouldAvoidDrowning()) {
            if (!mod.getClientBaritone().getPathingBehavior().isPathing()) {
                if (mod.getPlayer().isTouchingWater() && mod.getPlayer().getAir() < mod.getPlayer().getMaxAir()) {
                    // Swim up!
                    DirectInput.setJumping(true);
                    avoidedDrowning = true;
                    wasAvoidingDrowning = true;
                }
            }
        }
        // Stop swimming up if we just swam.
        if (wasAvoidingDrowning && !avoidedDrowning) {
            wasAvoidingDrowning = false;
            DirectInput.setJumping(false);
        }
    }

    /**
     * Checks if the player is actively moving toward lava blocks 1-2 blocks ahead.
     * Velocity-based: only triggers when walking toward lava, not just standing near it.
     */
    private boolean isAboutToWalkIntoLava(AltoClef mod) {
        if (mod.getPlayer().isInLava()) return false; // handled by isInLavaOhShit
        if (mod.getPlayer().hasStatusEffect(StatusEffects.FIRE_RESISTANCE)) return false;

        Vec3d vel = mod.getPlayer().getVelocity();
        double speed = vel.horizontalLength();
        if (speed < 0.01) return false; // not moving

        double nx = vel.x / speed;
        double nz = vel.z / speed;
        Vec3d pos = mod.getPlayer().getPos();
        int feetY = mod.getPlayer().getBlockPos().getY();

        // Check 1 and 2 blocks ahead in movement direction, at feet and one below
        for (double dist = 1.0; dist <= 2.0; dist += 1.0) {
            int checkX = (int) Math.floor(pos.x + nx * dist);
            int checkZ = (int) Math.floor(pos.z + nz * dist);
            for (int dy = -1; dy <= 0; dy++) {
                BlockPos check = new BlockPos(checkX, feetY + dy, checkZ);
                if (mod.getWorld().getBlockState(check).getBlock() == Blocks.LAVA) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isInLavaOhShit(AltoClef mod) {
        if (mod.getPlayer().isInLava() && !mod.getPlayer().hasStatusEffect(StatusEffects.FIRE_RESISTANCE)) {
            wasInLavaTimer.reset();
            return true;
        }
        return mod.getPlayer().isOnFire() && !wasInLavaTimer.elapsed();
    }

    private boolean isInFire(AltoClef mod) {
        if (mod.getPlayer().isOnFire() && !mod.getPlayer().hasStatusEffect(StatusEffects.FIRE_RESISTANCE)) {
            for (BlockPos pos : WorldHelper.getBlocksTouchingPlayer()) {
                Block b = mod.getWorld().getBlockState(pos).getBlock();
                if (b instanceof AbstractFireBlock) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isStuckInNetherPortal() {
        return WorldHelper.isInNetherPortal()
                && !AltoClef.getInstance().getUserTaskChain().getCurrentTask().thisOrChildSatisfies(task -> task instanceof EnterNetherPortalTask);
    }

    @Override
    public String getName() {
        return "Misc World Survival Chain";
    }

    @Override
    public boolean isActive() {
        // Always check for survival.
        return true;
    }

    @Override
    protected void onStop() {
        super.onStop();
    }
}
