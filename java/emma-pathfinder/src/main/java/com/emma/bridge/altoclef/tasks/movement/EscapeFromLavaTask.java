package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.multiversion.FoodComponentWrapper;
import adris.altoclef.multiversion.item.ItemVer;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.control.BlockInteraction;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import baritone.api.pathing.goals.Goal;
import adris.altoclef.control.DirectInput;
import baritone.pathing.movement.MovementHelper;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

import java.util.Optional;
import java.util.function.Predicate;

public class EscapeFromLavaTask extends CustomBaritoneGoalTask {

    private final float strength;
    private boolean eatingInLava = false;
    private final Predicate<BlockPos> avoidPlacingRiskyBlock;

    public EscapeFromLavaTask(AltoClef mod, float strength) {
        this.strength = strength;
        avoidPlacingRiskyBlock = (blockPos -> mod.getPlayer().getBoundingBox().intersects(new Box(blockPos))
                && (mod.getWorld().getBlockState(mod.getPlayer().getBlockPos().down()).getBlock() == Blocks.LAVA || mod.getPlayer().isInLava()));
    }

    public EscapeFromLavaTask(AltoClef mod) {
        this(mod, 100);
    }

    @Override
    protected void onStart() {
        AltoClef mod = AltoClef.getInstance();

        mod.getBehaviour().push();
        mod.getClientBaritone().getExploreProcess().onLostControl();
        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
        mod.getBehaviour().allowSwimThroughLava(true);
        mod.getBehaviour().setBlockPlacePenalty(0);
        mod.getBehaviour().setBlockBreakAdditionalPenalty(0);
        // do NOT ever wander
        checker = new MovementProgressChecker((int) Float.POSITIVE_INFINITY);

        mod.getExtraBaritoneSettings().avoidBlockPlace(avoidPlacingRiskyBlock);
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();

        // Only hold JUMP+SPRINT when submerged in lava
        if (mod.getWorld().getBlockState(mod.getPlayer().getBlockPos().up()).getBlock() == Blocks.LAVA) {
            DirectInput.setJumping(true);
            DirectInput.setSprinting(true);
        }

        // Try to eat food while in lava
        Optional<Item> food = calculateFood(mod);
        if (food.isPresent() && mod.getPlayer().getHungerManager().getFoodLevel() < 20) {
            if (mod.getPlayer().isBlocking()) {
                DirectInput.setUseHeld(false);
                mod.getPlayer().stopUsingItem();
                eatingInLava = false;
            } else {
                mod.getSlotHandler().forceEquipItem(new Item[]{food.get()}, true);
                // Self-heal: re-fire interactItem if eating was interrupted (lava damage)
                if (!eatingInLava || !mod.getPlayer().isUsingItem()) {
                    MinecraftClient.getInstance().interactionManager.interactItem(mod.getPlayer(), Hand.MAIN_HAND);
                    eatingInLava = true;
                }
                // Keep use key held — vanilla cancels eating when useKey isn't pressed
                DirectInput.setUseHeld(true);
            }
        }

        if (mod.getPlayer().isInLava() || mod.getWorld().getBlockState(mod.getPlayer().getBlockPos().down()).getBlock() == Blocks.LAVA) {
            setDebugState("run away from lava");

            // Check if any adjacent block is NOT lava — if so, Baritone can path out
            BlockPos steppingPos = mod.getPlayer().getSteppingPos();
            if (!mod.getWorld().getBlockState(steppingPos.east()).getBlock().equals(Blocks.LAVA) ||
                    !mod.getWorld().getBlockState(steppingPos.west()).getBlock().equals(Blocks.LAVA) ||
                    !mod.getWorld().getBlockState(steppingPos.south()).getBlock().equals(Blocks.LAVA) ||
                    !mod.getWorld().getBlockState(steppingPos.north()).getBlock().equals(Blocks.LAVA) ||
                    !mod.getWorld().getBlockState(steppingPos.east().north()).getBlock().equals(Blocks.LAVA) ||
                    !mod.getWorld().getBlockState(steppingPos.east().south()).getBlock().equals(Blocks.LAVA) ||
                    !mod.getWorld().getBlockState(steppingPos.west().north()).getBlock().equals(Blocks.LAVA) ||
                    !mod.getWorld().getBlockState(steppingPos.west().south()).getBlock().equals(Blocks.LAVA)) {
                return super.onTick();
            }

            // Fully surrounded by lava — manually find a surface to place a block on
            if (mod.getPlayer().isBlocking()) {
                DirectInput.setUseHeld(false);
                mod.getPlayer().stopUsingItem();
                eatingInLava = false;
            }

            BlockPos playerPos = mod.getPlayer().getSteppingPos();
            for (int dy = 0; dy >= -4; dy--) {
                for (int dx = -4; dx <= 4; dx++) {
                    for (int dz = -4; dz <= 4; dz++) {
                        BlockPos candidate = playerPos.add(dx, dy, dz);
                        if (!mod.getWorld().getBlockState(candidate).isSolidBlock(mod.getWorld(), candidate))
                            continue;
                        for (Direction face : Direction.values()) {
                            if (face == Direction.UP) continue;
                            BlockPos placeAt = candidate.offset(face);
                            if (placeAt.getY() > playerPos.getY()) continue;
                            if (!mod.getWorld().getBlockState(placeAt).isReplaceable()) continue;
                            if (mod.getPlayer().getBoundingBox().intersects(new Box(placeAt))) continue;

                            if (!BlockInteraction.isInReach(mod, candidate)) continue;

                            LookHelper.lookAt(mod, candidate); // cosmetic
                            if (mod.getItemStorage().hasItem(Items.NETHERRACK)) {
                                mod.getSlotHandler().forceEquipItem(Items.NETHERRACK);
                            } else {
                                mod.getSlotHandler().forceEquipItem(
                                        mod.getClientBaritoneSettings().acceptableThrowawayItems.value.toArray(new Item[0]));
                            }
                            BlockInteraction.rightClickBlock(mod, candidate, face);
                            return null;
                        }
                    }
                }
            }
        }

        return super.onTick();
    }

    private Optional<Item> calculateFood(AltoClef mod) {
        Item bestFood = null;
        double bestFoodScore = Double.NEGATIVE_INFINITY;
        ClientPlayerEntity player = mod.getPlayer();

        float hunger = player != null ? player.getHungerManager().getFoodLevel() : 20;
        float saturation = player != null ? player.getHungerManager().getSaturationLevel() : 20;

        for (ItemStack stack : mod.getItemStorage().getItemStacksPlayerInventory(true)) {
            if (ItemVer.isFood(stack)) {
                if (stack.getItem() == Items.SPIDER_EYE) continue;

                float score = getScore(stack, hunger, saturation);
                if (score > bestFoodScore) {
                    bestFoodScore = score;
                    bestFood = stack.getItem();
                }
            }
        }
        return Optional.ofNullable(bestFood);
    }

    private static float getScore(ItemStack stack, float hunger, float saturation) {
        FoodComponentWrapper food = ItemVer.getFoodComponent(stack.getItem());
        assert food != null;

        float hungerIfEaten = Math.min(hunger + food.getHunger(), 20);
        float saturationIfEaten = Math.min(hungerIfEaten, saturation + food.getSaturationModifier());
        float gainedSaturation = saturationIfEaten - saturation;

        float score = gainedSaturation * 10 - (20 - hungerIfEaten) * 2;
        if (stack.getItem() == Items.ROTTEN_FLESH) score = 0;
        return score;
    }

    @Override
    protected void onStop(Task interruptTask) {
        AltoClef mod = AltoClef.getInstance();

        mod.getBehaviour().pop();
        DirectInput.setJumping(false);
        DirectInput.setSprinting(false);
        DirectInput.setUseHeld(false);
        mod.getPlayer().stopUsingItem();
        eatingInLava = false;

        synchronized (mod.getExtraBaritoneSettings().getPlaceMutex()) {
            mod.getExtraBaritoneSettings().getPlaceAvoiders().remove(avoidPlacingRiskyBlock);
        }
    }

    @Override
    protected Goal newGoal(AltoClef mod) {
        return new EscapeFromLavaGoal();
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof EscapeFromLavaTask;
    }

    @Override
    public boolean isFinished() {
        ClientPlayerEntity player = AltoClef.getInstance().getPlayer();
        return !player.isInLava() && !player.isOnFire();
    }

    @Override
    protected String toDebugString() {
        return "Escaping lava";
    }

    private class EscapeFromLavaGoal implements Goal {
        private static boolean isLava(int x, int y, int z) {
            if (MinecraftClient.getInstance().world == null) return false;
            return MovementHelper.isLava(MinecraftClient.getInstance().world.getBlockState(new BlockPos(x, y, z)));
        }

        private static boolean isLavaAdjacent(int x, int y, int z) {
            return isLava(x + 1, y, z) || isLava(x - 1, y, z) || isLava(x, y, z + 1) || isLava(x, y, z - 1)
                    || isLava(x + 1, y, z - 1) || isLava(x + 1, y, z + 1) || isLava(x - 1, y, z - 1)
                    || isLava(x - 1, y, z + 1);
        }

        private static boolean isWater(int x, int y, int z) {
            if (MinecraftClient.getInstance().world == null) return false;
            return MovementHelper.isWater(MinecraftClient.getInstance().world.getBlockState(new BlockPos(x, y, z)));
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            return !isLava(x, y, z) && !isLavaAdjacent(x, y, z);
        }

        @Override
        public double heuristic(int x, int y, int z) {
            if (isLava(x, y, z)) return strength;
            if (isLavaAdjacent(x, y, z)) return strength * 0.5f;
            if (isWater(x, y, z)) return -100;
            return 0;
        }
    }
}
