package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.control.DirectInput;
import adris.altoclef.tasks.movement.MLGBucketTask;
import adris.altoclef.tasksystem.ITaskOverridesGrounded;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.control.BlockInteraction;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Hand;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

@SuppressWarnings("UnnecessaryLocalVariable")
public class MLGBucketFallChain extends SingleTaskChain implements ITaskOverridesGrounded {

    private final TimerGame pickupRepeatTimer = new TimerGame(0.25);
    private final TimerGame waterBlockTimeout = new TimerGame(2);
    private MLGBucketTask lastMLG = null;
    private boolean wasPickingUp = false;
    private boolean doingChorusFruit = false;

    public MLGBucketFallChain(TaskRunner runner) {
        super(runner);
    }

    @Override
    protected void onTaskFinish(AltoClef mod) {
        //_lastMLG = null;
    }

    @Override
    public float getPriority() {
        if (!AltoClef.inGame()) return Float.NEGATIVE_INFINITY;

        AltoClef mod = AltoClef.getInstance();

        if (isFalling(mod)) {
            waterBlockTimeout.reset();
            setTask(new MLGBucketTask());
            lastMLG = (MLGBucketTask) mainTask;
            return 100;
        } else if (lastMLG != null) {
            // We placed water via MLG — try to pick it up.
            if (mod.getItemStorage().hasItem(Items.BUCKET)
                    && !mod.getItemStorage().hasItem(Items.WATER_BUCKET)) {
                BlockPos placed = lastMLG.getWaterPlacedPos();
                boolean isPlacedWater = false;
                try {
                    isPlacedWater = placed != null
                            && mod.getWorld().getBlockState(placed).getBlock() == Blocks.WATER;
                } catch (Exception e) { /* ignore */ }

                if (placed != null
                        && placed.isWithinDistance(mod.getPlayer().getPos(), 5.5)
                        && isPlacedWater) {
                    if (!BlockInteraction.isInReach(mod, placed)
                            || mod.getPlayer().getBlockPos().equals(placed)) {
                        // Too far away or player inside the block — delegate to task.
                        setTask(TaskCatalogue.getItemTask(Items.WATER_BUCKET, 1));
                    } else if (pickupRepeatTimer.elapsed()) {
                        pickupRepeatTimer.reset();
                        if (BlockInteraction.tryCollectFluid(mod, placed)) {
                            wasPickingUp = true;
                        }
                    }
                    return 60;
                }
                // Water not visible yet — wait for server block-update (up to 2 s).
                if (!waterBlockTimeout.elapsed()) {
                    return 60;
                }
            }
            lastMLG = null; // water collected, gone, or timeout elapsed — clean up
        }
        if (wasPickingUp) {
            wasPickingUp = false;
            lastMLG = null;
        }
        net.minecraft.entity.effect.StatusEffectInstance levitation = mod.getPlayer().getStatusEffect(StatusEffects.LEVITATION);
        if (levitation != null &&
                !mod.getPlayer().getItemCooldownManager().isCoolingDown(Items.CHORUS_FRUIT.getDefaultStack()) &&
                levitation.getDuration() <= 70 &&
                mod.getItemStorage().hasItemInventoryOnly(Items.CHORUS_FRUIT) &&
                !mod.getItemStorage().hasItemInventoryOnly(Items.WATER_BUCKET)) {
            mod.getSlotHandler().forceEquipItem(Items.CHORUS_FRUIT);
            // Self-heal: re-fire interactItem if chorus eating was interrupted
            if (!doingChorusFruit || !mod.getPlayer().isUsingItem()) {
                doingChorusFruit = true;
                MinecraftClient.getInstance().interactionManager.interactItem(mod.getPlayer(), Hand.MAIN_HAND);
            }
            // Keep use key held — vanilla cancels eating when useKey isn't pressed
            DirectInput.setUseHeld(true);
            mod.getExtraBaritoneSettings().setInteractionPaused(true);
        } else if (doingChorusFruit) {
            doingChorusFruit = false;
            DirectInput.setUseHeld(false);
            mod.getPlayer().stopUsingItem();
            mod.getExtraBaritoneSettings().setInteractionPaused(false);
        }
        lastMLG = null;
        return Float.NEGATIVE_INFINITY;
    }

    @Override
    public String getName() {
        return "MLG Water Bucket Fall Chain";
    }

    @Override
    public boolean isActive() {
        // We're always checking for mlg.
        return true;
    }

    public boolean doneMLG() {
        return lastMLG == null;
    }

    public boolean isChorusFruiting() {
        return doingChorusFruit;
    }

    public boolean isFalling(AltoClef mod) {
        if (!mod.getModSettings().shouldAutoMLGBucket()) {
            return false;
        }
        if (mod.getPlayer().isSwimming() || mod.getPlayer().isTouchingWater() || mod.getPlayer().isOnGround() || mod.getPlayer().isClimbing()) {
            // We're grounded.
            return false;
        }
        double ySpeed = mod.getPlayer().getVelocity().y;
        return ySpeed < -0.7;
    }
}
