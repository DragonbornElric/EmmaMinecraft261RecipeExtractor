package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Settings;
import adris.altoclef.control.DirectInput;
import adris.altoclef.multiversion.FoodComponentWrapper;
import adris.altoclef.multiversion.item.ItemVer;
import adris.altoclef.tasks.resources.CollectFoodTask;
import adris.altoclef.tasks.slot.WithdrawFromOverflowTask;
import adris.altoclef.tasks.speedrun.DragonBreathTracker;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.*;
import adris.altoclef.util.slots.PlayerSlot;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.util.Hand;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Pair;
import net.minecraft.util.math.BlockPos;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public class FoodChain extends SingleTaskChain {
    private static FoodChainConfig config;
    private static boolean hasFood;

    static {
        ConfigHelper.loadConfig("configs/food_chain_settings.json", FoodChainConfig::new, FoodChainConfig.class, newConfig -> config = newConfig);
    }

    private final DragonBreathTracker dragonBreathTracker = new DragonBreathTracker();
    private boolean isTryingToEat = false;
    private boolean eatingStarted = false;
    private boolean requestFillup = false;
    private boolean needsFood = false;
    private Optional<Item> cachedPerfectFood = Optional.empty();
    private boolean shouldStop = false;
    private boolean overflowWithdrawAttempted = false;

    public FoodChain(TaskRunner runner) {
        super(runner);
    }

    @Override
    protected void onTaskFinish(AltoClef mod) {
        // Nothing.
    }

    private void startEat(AltoClef mod, Item food) {
        //Debug.logInternal("EATING " + toUse.getTranslationKey() + " : " + test);
        if (mod.getPlayer().isBlocking()) {
            mod.log("want to eat, trying to stop shielding...");
            mod.getPlayer().stopUsingItem();
            eatingStarted = false;
            return;
        }

        isTryingToEat = true;
        requestFillup = true;
        mod.getSlotHandler().forceEquipItem(new Item[]{food}, true); //"true" because it's food
        // Self-heal: re-fire interactItem if eating was interrupted (damage, knockback)
        if (!eatingStarted || !mod.getPlayer().isUsingItem()) {
            MinecraftClient.getInstance().interactionManager.interactItem(mod.getPlayer(), Hand.MAIN_HAND);
            eatingStarted = true;
        }
        // Keep use key held each tick — vanilla handleInputEvents() cancels eating
        // when useKey.isPressed() is false.  Phase 52 removed setPressed() for
        // movement keys (Baritone ControlledInput conflict); use key is safe.
        DirectInput.setUseHeld(true);
        mod.getExtraBaritoneSettings().setInteractionPaused(true);
    }

    private void stopEat() {
        if (isTryingToEat) {
            DirectInput.setUseHeld(false);
            AltoClef altoClef = AltoClef.getInstance();

            if (altoClef.getItemStorage().hasItem(Items.SHIELD) || altoClef.getItemStorage().hasItemInOffhand(Items.SHIELD)) {
                if (StorageHelper.getItemStackInSlot(PlayerSlot.OFFHAND_SLOT).getItem() != Items.SHIELD) {
                    altoClef.getSlotHandler().forceEquipItemToOffhand(Items.SHIELD);
                } else {
                    isTryingToEat = false;
                    requestFillup = false;
                }
            } else {
                isTryingToEat = false;
                requestFillup = false;
            }
            altoClef.getPlayer().stopUsingItem();
            eatingStarted = false;
            altoClef.getExtraBaritoneSettings().setInteractionPaused(false);
        }
    }

    public boolean isTryingToEat() {
        return isTryingToEat;
    }

    @Override
    public float getPriority() {
        AltoClef mod = AltoClef.getInstance();

        if (WorldHelper.isInNetherPortal()) {
            stopEat();
            return Float.NEGATIVE_INFINITY;
        }
        // do not interrupt defending from mobs by eating
        if (mod.getMobDefenseChain().isPuttingOutFire()
                || mod.getMobDefenseChain().isShielding()
                || mod.getPlayer().isBlocking()
                || mod.getMobDefenseChain().isDoingAcrobatics()
        ) {
            stopEat();
            return Float.NEGATIVE_INFINITY;
        }
        dragonBreathTracker.updateBreath(mod);
        for (BlockPos playerIn : WorldHelper.getBlocksTouchingPlayer()) {
            if (dragonBreathTracker.isTouchingDragonBreath(playerIn)) {
                stopEat();
                return Float.NEGATIVE_INFINITY;
            }
        }
        if (!mod.getModSettings().isAutoEat()) {
            stopEat();
            return Float.NEGATIVE_INFINITY;
        }

        // do NOT eat while in lava if we are escaping it (spaghetti code dependencies go brrrr)
        if (mod.getPlayer().isInLava()) {
            stopEat();
            return Float.NEGATIVE_INFINITY;
        }

        /*
        - Eats if:
        - We're hungry and have food that fits
            - We're low on health and maybe a little bit hungry
            - We're very low on health and are even slightly hungry
        - We're kind of hungry and have food that fits perfectly
         */
        // We're in danger, don't eat now!!
        if (!mod.getMLGBucketChain().doneMLG() || mod.getMLGBucketChain().isFalling(mod) ||
                mod.getPlayer().isBlocking() || shouldStop) {
            stopEat();
            return Float.NEGATIVE_INFINITY;
        }
        Pair<Integer, Optional<Item>> calculation = calculateFood(mod);
        int cachedFoodScore = calculation.getLeft();
        cachedPerfectFood = calculation.getRight();
        hasFood = cachedFoodScore > 0;

        // No food in inventory — check overflow for food and withdraw it
        if (!hasFood) {
            int foodLevel = mod.getPlayer().getHungerManager().getFoodLevel();
            if (foodLevel < 20 && !overflowWithdrawAttempted) {
                ItemTarget[] overflowFood = findFoodInOverflow();
                if (overflowFood != null && overflowFood.length > 0) {
                    overflowWithdrawAttempted = true;
                    setTask(new WithdrawFromOverflowTask(overflowFood));
                    return 55f; // higher priority than CollectFoodTask (45f)
                }
            }
        }
        // Reset flag when food is available again (so it can re-trigger later)
        if (hasFood) overflowWithdrawAttempted = false;

        // If we requested a fillup but we're full, stop.
        if (requestFillup && mod.getPlayer().getHungerManager().getFoodLevel() >= 20) {
            requestFillup = false;
        }
        // If we no longer have food, we no longer can eat.
        if (!hasFood) {
            requestFillup = false;
        }

        if (hasFood && (needsToEat() || requestFillup) && cachedPerfectFood.isPresent() &&
                !mod.getMLGBucketChain().isChorusFruiting() && !mod.getPlayer().isBlocking() &&
                !areEnemiesNearby(mod)) {

            Item toUse = cachedPerfectFood.get();

            // Make sure we're not facing a container
            if (!LookHelper.tryAvoidingInteractable(mod)) {
                return Float.NEGATIVE_INFINITY;
            }
            startEat(mod, toUse);
        } else {
            stopEat();
        }

        Settings settings = mod.getModSettings();

        // Don't start food collection expeditions while hostiles are nearby
        if (areEnemiesNearby(mod)) {
            return Float.NEGATIVE_INFINITY;
        }

        if (needsFood || cachedFoodScore < settings.getMinimumFoodAllowed()) {
            needsFood = cachedFoodScore < settings.getFoodUnitsToCollect();

            // Only collect if we don't have enough food.
            // If the user inputs invalid settings, the bot would get stuck here.
            if (cachedFoodScore < settings.getFoodUnitsToCollect()) {
                setTask(new CollectFoodTask(settings.getFoodUnitsToCollect()));
                return 45f;
            }
        }


        // Food eating is handled asynchronously.
        return Float.NEGATIVE_INFINITY;
    }

    private boolean areEnemiesNearby(AltoClef mod) {
        // While eating: only interrupt for melee-range threats (4 blocks).
        // Otherwise: don't start eating if hostiles within 10 blocks.
        // 10 matches MobDefenseChain's annoyingRange for melee hostiles, and
        // prevents skeleton knockback → eat → arrow interrupt oscillation.
        // Use isProbablyHostileToPlayer instead of raw instanceof HostileEntity —
        // neutral piglins, calm endermen, and zombified piglins shouldn't block eating.
        double radius = isTryingToEat ? 4.0 : 10.0;
        for (Entity entity : mod.getEntityTracker().getCloseEntities()) {
            if (entity instanceof HostileEntity hostile
                    && hostile.distanceTo(mod.getPlayer()) < radius
                    && EntityHelper.isProbablyHostileToPlayer(mod, entity)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isActive() {
        // We're always checking for food.
        return true;
    }

    @Override
    public String getName() {
        return "Food";
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopEat();
    }

    public boolean needsToEat() {
        if (!hasFood() || shouldStop) {
            return false;
        }


        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        assert player != null;
        int foodLevel = player.getHungerManager().getFoodLevel();
        float health = player.getHealth();

        if (foodLevel >= 20) {
            // We can't eat.
            return false;
        }

        if (health <= 10) {
            return true;
        }
        //Debug.logMessage("FOOD: " + foodLevel + " -- HEALTH: " + health);

        // Eat if we're desperate/need to heal ASAP
        if (player.isOnFire() || player.hasStatusEffect(StatusEffects.WITHER) || health < config.alwaysEatWhenWitherOrFireAndHealthBelow) {
            return true;
        } else if (foodLevel > config.alwaysEatWhenBelowHunger) {
            if (health < config.alwaysEatWhenBelowHealth) {
                return true;
            }
        } else {
            // We have half hunger
            return true;
        }


        // Eat if we're  units hungry and we have a perfect fit.
        if (foodLevel < config.alwaysEatWhenBelowHungerAndPerfectFit && cachedPerfectFood.isPresent()) {
            int need = 20 - foodLevel;
            Item best = cachedPerfectFood.get();

            int fills = (ItemVer.getFoodComponent(best) != null) ? ItemVer.getFoodComponent(best).getHunger() : -1;
            return fills == need;
        }

        return false;
    }

    private Pair<Integer, Optional<Item>> calculateFood(AltoClef mod) {
        Item bestFood = null;
        double bestFoodScore = Double.NEGATIVE_INFINITY;
        int foodTotal = 0;
        ClientPlayerEntity player = mod.getPlayer();
        float health = player != null ? player.getHealth() : 20;
        //float toHeal = player != null? 20 - player.getHealth() : 0;
        float hunger = player != null ? player.getHungerManager().getFoodLevel() : 20;
        float saturation = player != null ? player.getHungerManager().getSaturationLevel() : 20;
        // Get best food item + calculate food total
        for (ItemStack stack : mod.getItemStorage().getItemStacksPlayerInventory(true)) {
            if (ItemVer.isFood(stack)) {
                // Ignore protected items
                if (!ItemHelper.canThrowAwayStack(mod, stack)) continue;

                // Ignore spider eyes
                if (stack.getItem() == Items.SPIDER_EYE) {
                    continue;
                }

                FoodComponentWrapper food = ItemVer.getFoodComponent(stack.getItem());

                assert food != null;
                float hungerIfEaten = Math.min(hunger + food.getHunger(), 20);
                float saturationIfEaten = Math.min(hungerIfEaten, saturation + food.getSaturationModifier());
                float gainedSaturation = (saturationIfEaten - saturation);
                float gainedHunger = (hungerIfEaten - hunger);
                float hungerNotFilled = 20 - hungerIfEaten;

                float saturationWasted = food.getSaturationModifier() - gainedSaturation;
                float hungerWasted = food.getHunger() - gainedHunger;

                boolean prioritizeSaturation = health < config.prioritizeSaturationWhenBelowHealth;
                float saturationGoodScore = prioritizeSaturation ? gainedSaturation * config.foodPickPrioritizeSaturationSaturationMultiplier : gainedSaturation;
                float saturationLossPenalty = prioritizeSaturation ? 0 : saturationWasted * config.foodPickSaturationWastePenaltyMultiplier;
                float hungerLossPenalty = hungerWasted * config.foodPickHungerWastePenaltyMultiplier;
                float hungerNotFilledPenalty = hungerNotFilled * config.foodPickHungerNotFilledPenaltyMultiplier;

                float score = saturationGoodScore - saturationLossPenalty - hungerLossPenalty - hungerNotFilledPenalty;

                if (stack.getItem() == Items.ROTTEN_FLESH) {
                    score -= config.foodPickRottenFleshPenalty;
                }
                if (score > bestFoodScore) {
                    bestFoodScore = score;
                    bestFood = stack.getItem();
                }

                foodTotal += Objects.requireNonNull(ItemVer.getFoodComponent(stack.getItem())).getHunger() * stack.getCount();
            }
        }

        return new Pair<>(foodTotal, Optional.ofNullable(bestFood));
    }

    /**
     * Check the overflow cache for food items and return ItemTargets for withdrawal.
     * Returns null if overflow is unavailable or has no food.
     */
    private ItemTarget[] findFoodInOverflow() {
        try {
            if (!com.emma.overflow.OverflowClientMod.OverflowClientApi.isAvailable()) return null;

            JsonObject cached = com.emma.overflow.OverflowClientMod.OverflowClientApi.getCachedStatus();
            if (cached == null || !cached.has("items")) return null;

            List<ItemTarget> foodTargets = new ArrayList<>();
            for (JsonElement elem : cached.getAsJsonArray("items")) {
                JsonObject obj = elem.getAsJsonObject();
                String itemId = obj.get("item").getAsString();
                int count = obj.get("count").getAsInt();

                // Resolve item and check if it's food
                net.minecraft.util.Identifier id = net.minecraft.util.Identifier.of(itemId);
                Item item = Registries.ITEM.get(id);
                if (item != null && ItemVer.isFood(item) && item != Items.SPIDER_EYE) {
                    foodTargets.add(new ItemTarget(item, count));
                }
            }

            return foodTargets.isEmpty() ? null : foodTargets.toArray(new ItemTarget[0]);
        } catch (NoClassDefFoundError ignored) {
            return null;
        }
    }

    // If we need to eat like, NOW.
    public boolean needsToEatCritical() {
        return false;
    }

    public boolean hasFood() {
        return hasFood;
    }

    public void shouldStop(boolean shouldStopInput) {
        shouldStop = shouldStopInput;
    }

    public boolean isShouldStop() {
        return shouldStop;
    }

    static class FoodChainConfig {
        public int alwaysEatWhenWitherOrFireAndHealthBelow = 6;
        public int alwaysEatWhenBelowHunger = 10;
        public int alwaysEatWhenBelowHealth = 14;
        public int alwaysEatWhenBelowHungerAndPerfectFit = 20 - 5;
        public int prioritizeSaturationWhenBelowHealth = 8;
        public float foodPickPrioritizeSaturationSaturationMultiplier = 8;
        public float foodPickSaturationWastePenaltyMultiplier = 1;
        public float foodPickHungerWastePenaltyMultiplier = 2;
        public float foodPickHungerNotFilledPenaltyMultiplier = 1;
        public float foodPickRottenFleshPenalty = 100;
        public float runDontEatMaxHealth = 3;
        public int runDontEatMaxHunger = 3;
        public int canTankHitsAndEatArmor = 15;
        public int canTankHitsAndEatMaxHunger = 3;
    }
}
