package com.emma.bridge.goap.actions;

import emmatone.api.EmmatoneAPI;
import com.emma.bridge.EmmaBridgeMod;
import com.emma.bridge.catalogue.ItemRecipeEntry;
import com.emma.bridge.catalogue.ItemRecipeRegistry;
import com.emma.bridge.goap.BaseRegistry;
import com.emma.bridge.goap.GoapAction;
import com.emma.bridge.goap.GoalSet;
import com.emma.bridge.goap.WorldState;
import com.emma.bridge.util.CombatHelper;
import com.emma.bridge.util.InventoryScanner;
import com.emma.bridge.util.ItemClassifier;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.util.*;

/**
 * Collect food when food supply is low — fully data-driven via ItemRecipeRegistry
 * and MC's DataComponents.FOOD.
 *
 * Two operating modes:
 *   Mode A (Direct): food obtainable in 1 step — pickup ground items, harvest crops, hunt animals
 *   Mode B (Goal Chain): food requires 2+ steps — adds a have_item goal to GoalSet, yields to
 *                        CraftItemAction/SmeltItemAction/MineBlockAction
 *
 * Score: stay_fed priority × urgency (higher when food count is low)
 * Category: resource_hoarding
 */
public class CollectFoodAction extends GoapAction {

    public static final int LOW_FOOD_THRESHOLD = 8;    // start collecting before empty
    private static final int TARGET_FOOD_COUNT = 16;    // collect a meaningful supply
    private static final int GOAL_STALL_TIMEOUT = 200;  // 10 seconds (crafting goals)
    private static final int NAV_STALL_TIMEOUT = 600;   // 30 seconds (navigation goals)
    /** Sea level — secondary underground check for open caverns with stray skylight. */
    private static final int SEA_LEVEL_Y = 60;

    private enum Phase { EVALUATE, DIRECT_GATHER, GOAL_CHAIN_MONITOR }
    private enum GatherStrategy { PICKUP_ITEM, HARVEST_BLOCK, HUNT_ANIMAL }

    private Phase phase = Phase.EVALUATE;
    private boolean active = false;
    private GatherStrategy gatherStrategy;
    private Entity targetEntity;                // animal or ground item entity

    // Mode B: goal chain
    private String activeFoodGoalId;            // non-null when Mode B is active
    private int goalStallTicks;
    private int lastFoodCount;                  // for stall detection
    private float lastComputedScore;            // cached for goal priority

    // Navigation goal tracking (Priority 5 & 6)
    private boolean isNavigationGoal;           // true when goal is navigate_to (base or surface)
    private double lastDistanceToTarget;        // for navigation stall detection
    private int navTargetX, navTargetY, navTargetZ;  // cached target for distance checks

    // GoalSet + BaseRegistry references (injected by EmmaBridgeClient wiring)
    private GoalSet goalSetRef;
    private BaseRegistry baseRegistryRef;
    private WorldState cachedWorldState;  // cached from last computeScore for use in tick

    public void setGoalSet(GoalSet goals) {
        this.goalSetRef = goals;
    }

    public void setBaseRegistry(BaseRegistry registry) {
        this.baseRegistryRef = registry;
    }

    @Override
    public String getName() {
        return "CollectFood";
    }

    @Override
    public String personalityCategory() {
        return "resource_hoarding";
    }

    @Override
    public boolean checkPreconditions(WorldState state) {
        if (activeFoodGoalId != null) return false;
        return state.foodItemCount < LOW_FOOD_THRESHOLD;
    }

    @Override
    public float computeScore(WorldState state, GoalSet goals) {
        // If a food goal chain is active, yield to other actions
        if (activeFoodGoalId != null) {
            // Check if food goal was fulfilled
            if (state.foodItemCount >= LOW_FOOD_THRESHOLD) {
                cleanupFoodGoal();
            }
            return 0f;
        }

        if (state.foodItemCount >= LOW_FOOD_THRESHOLD) return 0f;

        cachedWorldState = state;
        float stayFed = goals.getGoalPriority("stay_fed");
        if (stayFed <= 0) stayFed = 5.0f;

        float urgency = 1.0f - ((float) state.foodItemCount / LOW_FOOD_THRESHOLD);
        urgency = Math.max(0.3f, urgency);

        if (state.hunger < 10) urgency *= 1.3f;
        if (state.hunger < 5) urgency *= 1.5f;

        lastComputedScore = stayFed * urgency;
        return lastComputedScore;
    }

    @Override
    public void execute(Minecraft client) {
        active = true;
        phase = Phase.EVALUATE;
    }

    @Override
    public void tick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) return;

        switch (phase) {
            case EVALUATE -> evaluateAndAct(client, player);
            case DIRECT_GATHER -> tickDirectGather(client, player);
            case GOAL_CHAIN_MONITOR -> tickGoalChainMonitor(player);
        }
    }

    // ── Evaluate best food source ───────────────────────────────

    private void evaluateAndAct(Minecraft client, LocalPlayer player) {
        // Check if we already have enough
        if (countFoodInInventory(player) >= TARGET_FOOD_COUNT) {
            active = false;
            return;
        }

        // Priority 1: Ground food items (zero effort)
        Entity groundFood = findNearestGroundFood(player);
        if (groundFood != null) {
            targetEntity = groundFood;
            gatherStrategy = GatherStrategy.PICKUP_ITEM;
            phase = Phase.DIRECT_GATHER;
            EmmatoneAPI.getProvider().getPrimaryEmmatone().getCustomGoalProcess()
                    .setGoalAndPath(new emmatone.api.pathing.goals.GoalNear(
                            groundFood.blockPosition(), 1));
            EmmaBridgeMod.LOGGER.info("[CollectFood] Found ground food item, picking up");
            return;
        }

        // Priority 2: Food blocks/crops nearby (low effort, no combat)
        // Use Emmatone's FarmProcess which handles harvest + replant automatically
        Set<String> foodBlocks = ItemRecipeRegistry.getFoodSourceBlocks();
        boolean hasFoodBlocksNearby = false;
        for (String block : foodBlocks) {
            String fullBlock = block.contains(":") ? block : "minecraft:" + block;
            List<BlockPos> nearby = findNearbyFoodBlocks(player, fullBlock);
            if (!nearby.isEmpty()) {
                hasFoodBlocksNearby = true;
                break;
            }
        }
        if (hasFoodBlocksNearby) {
            gatherStrategy = GatherStrategy.HARVEST_BLOCK;
            phase = Phase.DIRECT_GATHER;
            // FarmProcess handles pathfinding, harvesting mature crops, AND replanting
            EmmatoneAPI.getProvider().getPrimaryEmmatone()
                    .getFarmProcess().farm(32, player.blockPosition());
            EmmaBridgeMod.LOGGER.info("[CollectFood] Using FarmProcess to harvest nearby food crops");
            return;
        }

        // Priority 3: Food animals (medium effort, requires combat)
        Entity animal = findNearestFoodAnimal(player);
        if (animal != null) {
            targetEntity = animal;
            gatherStrategy = GatherStrategy.HUNT_ANIMAL;
            phase = Phase.DIRECT_GATHER;
            EmmatoneAPI.getProvider().getPrimaryEmmatone().getCustomGoalProcess()
                    .setGoalAndPath(new emmatone.api.pathing.goals.GoalNear(
                            animal.blockPosition(), 2));
            EmmaBridgeMod.LOGGER.info("[CollectFood] Hunting food animal: {}",
                    BuiltInRegistries.ENTITY_TYPE.getKey(animal.getType()).getPath());
            return;
        }

        // Priority 4: Multi-step food (Mode B — goal chain)
        if (goalSetRef != null) {
            String bestFood = findBestMultiStepFood(player);
            if (bestFood != null) {
                activateFoodGoalChain(bestFood);
                return;
            }
        }

        // Priority 5: Navigate to known base (underground with no local food sources)
        if (goalSetRef != null && isUndergroundOverworld(cachedWorldState)) {
            if (cachedWorldState.hasBase) {
                activateNavigateToBase(cachedWorldState);
                return;
            }

            // Priority 6: Navigate to surface using heightmap (underground, no base)
            activateNavigateToSurface(client, player);
            return;
        }

        // Nothing available on the surface — unusual edge case
        EmmaBridgeMod.LOGGER.info("[CollectFood] No food sources found nearby");
        active = false;
    }

    // ── Direct gather tick ──────────────────────────────────────

    private void tickDirectGather(Minecraft client, LocalPlayer player) {
        if (countFoodInInventory(player) >= TARGET_FOOD_COUNT) {
            active = false;
            cancelPathing();
            return;
        }

        switch (gatherStrategy) {
            case PICKUP_ITEM -> {
                if (targetEntity == null || targetEntity.isRemoved()) {
                    // Item picked up or despawned — re-evaluate
                    phase = Phase.EVALUATE;
                    return;
                }
                // Items auto-collect at ~1 block; just wait for entity removal
                // If pathing completed but item not collected, re-path
                if (!EmmatoneAPI.getProvider().getPrimaryEmmatone().getCustomGoalProcess().isActive()
                        && player.distanceTo(targetEntity) > 1.5) {
                    // Pathing finished but we're still far — re-issue
                    EmmatoneAPI.getProvider().getPrimaryEmmatone().getCustomGoalProcess()
                            .setGoalAndPath(new emmatone.api.pathing.goals.GoalNear(
                                    targetEntity.blockPosition(), 0));
                }
            }
            case HARVEST_BLOCK -> {
                // FarmProcess handles harvest + replant automatically
                if (!EmmatoneAPI.getProvider().getPrimaryEmmatone().getFarmProcess().isActive()) {
                    // Farming done — cancel pathing so next goal can take over cleanly
                    cancelPathing();
                    phase = Phase.EVALUATE;
                }
            }
            case HUNT_ANIMAL -> {
                if (targetEntity != null && (targetEntity.isRemoved() || !targetEntity.isAlive())) {
                    targetEntity = null;
                }
                if (targetEntity == null) {
                    // Animal died — re-evaluate for more food
                    phase = Phase.EVALUATE;
                    return;
                }
                if (player.distanceTo(targetEntity) < 3.5) {
                    CombatHelper.tryAttack(client, player, targetEntity);
                }
            }
        }
    }

    // ── Mode B: Goal chain ──────────────────────────────────────

    private void activateFoodGoalChain(String foodItemId) {
        activeFoodGoalId = "food_acquire_" + foodItemId;
        goalStallTicks = 0;

        JsonObject target = new JsonObject();
        target.addProperty("item", "minecraft:" + foodItemId);
        target.addProperty("count", 1);

        // Priority high enough that CraftItem (×0.8) and SmeltItem (×0.7) outscore CollectFood
        float goalPriority = lastComputedScore + 2.0f;

        goalSetRef.addDynamicGoal(new GoalSet.Goal(
                activeFoodGoalId, "have_item", goalPriority, target));

        EmmaBridgeMod.LOGGER.info("[CollectFood] Mode B: created goal chain for {} (priority {})",
                foodItemId, goalPriority);

        lastFoodCount = Minecraft.getInstance().player != null
                ? countFoodInInventory(Minecraft.getInstance().player) : 0;
        active = false; // yield to other actions
        phase = Phase.GOAL_CHAIN_MONITOR;
    }

    private void tickGoalChainMonitor(LocalPlayer player) {
        if (activeFoodGoalId == null) return;

        int currentFood = countFoodInInventory(player);
        if (currentFood >= LOW_FOOD_THRESHOLD) {
            cleanupFoodGoal();
            active = false;
            return;
        }

        // Stall detection — different strategies for crafting vs. navigation goals
        if (currentFood > lastFoodCount) {
            // Food increased — progress for any goal type
            goalStallTicks = 0;
            lastFoodCount = currentFood;
        } else if (isNavigationGoal) {
            // Navigation stall: check if bot is moving closer to target
            double dx = player.getX() - navTargetX;
            double dy = player.getY() - navTargetY;
            double dz = player.getZ() - navTargetZ;
            double currentDist = Math.sqrt(dx * dx + dy * dy + dz * dz);

            if (currentDist < lastDistanceToTarget - 1.0) {
                // Making progress toward target — reset stall
                goalStallTicks = 0;
                lastDistanceToTarget = currentDist;
            } else {
                goalStallTicks++;
            }

            if (goalStallTicks >= NAV_STALL_TIMEOUT) {
                EmmaBridgeMod.LOGGER.info("[CollectFood] Navigation goal stalled at dist={}, reassessing",
                        String.format("%.0f", currentDist));
                cleanupFoodGoal();
                phase = Phase.EVALUATE;
            }
        } else {
            // Crafting stall: food count unchanged
            goalStallTicks++;
            if (goalStallTicks >= GOAL_STALL_TIMEOUT) {
                EmmaBridgeMod.LOGGER.info("[CollectFood] Goal chain stalled, reassessing");
                cleanupFoodGoal();
                phase = Phase.EVALUATE;
            }
        }
    }

    private void cleanupFoodGoal() {
        if (activeFoodGoalId != null && goalSetRef != null) {
            goalSetRef.removeDynamicGoal(activeFoodGoalId);
            EmmaBridgeMod.LOGGER.info("[CollectFood] Cleaned up food goal: {}", activeFoodGoalId);
        }
        activeFoodGoalId = null;
        goalStallTicks = 0;
        isNavigationGoal = false;
        lastDistanceToTarget = Double.MAX_VALUE;
    }

    // ── Priority 5 & 6: Navigate to base / surface ─────────────

    /**
     * Check if the bot is underground in the overworld.
     * Two-pronged: no sky exposure (skyLight == 0) OR below sea level (Y < 60).
     * Only applies in the overworld — Nether/End always have skyLight 0.
     */
    private boolean isUndergroundOverworld(WorldState state) {
        if (state == null) return false;
        if (!state.dimension.equals("minecraft:overworld")) return false;
        return state.skyLight == 0 || state.posY < SEA_LEVEL_Y;
    }

    /**
     * Priority 5: Navigate to the nearest registered base.
     * Injects a navigate_to goal that NavigateToAction picks up.
     */
    private void activateNavigateToBase(WorldState state) {
        activeFoodGoalId = "food_return_to_base";
        goalStallTicks = 0;
        isNavigationGoal = true;
        navTargetX = state.baseX;
        navTargetY = state.baseY;
        navTargetZ = state.baseZ;
        lastDistanceToTarget = Math.sqrt(
                (state.posX - navTargetX) * (state.posX - navTargetX)
                + (state.posY - navTargetY) * (state.posY - navTargetY)
                + (state.posZ - navTargetZ) * (state.posZ - navTargetZ));

        JsonObject target = new JsonObject();
        target.addProperty("x", state.baseX);
        target.addProperty("y", state.baseY);
        target.addProperty("z", state.baseZ);

        float goalPriority = lastComputedScore + 2.0f;
        goalSetRef.addDynamicGoal(new GoalSet.Goal(
                activeFoodGoalId, "navigate_to", goalPriority, target));

        EmmaBridgeMod.LOGGER.info("[CollectFood] Priority 5: Underground with no food, navigating to base '{}' at ({},{},{})",
                state.baseName, state.baseX, state.baseY, state.baseZ);

        lastFoodCount = Minecraft.getInstance().player != null
                ? countFoodInInventory(Minecraft.getInstance().player) : 0;
        active = false; // yield to NavigateToAction
        phase = Phase.GOAL_CHAIN_MONITOR;
    }

    /**
     * Priority 6: Navigate to the surface when no base is registered.
     * Uses MOTION_BLOCKING heightmap to find real surface Y, then walks
     * down up to 5 blocks to find a standable (solid) block.
     */
    private void activateNavigateToSurface(Minecraft client, LocalPlayer player) {
        if (client.level == null) {
            EmmaBridgeMod.LOGGER.info("[CollectFood] No world available for heightmap lookup");
            active = false;
            return;
        }

        int px = player.blockPosition().getX();
        int pz = player.blockPosition().getZ();
        int heightmapY = client.level.getHeight(Heightmap.Types.MOTION_BLOCKING, px, pz) - 1;

        // Walk down from heightmap Y to find a standable surface (max 5 blocks)
        int surfaceY = heightmapY;
        for (int dy = 0; dy <= 5; dy++) {
            BlockPos check = new BlockPos(px, heightmapY - dy, pz);
            if (client.level.getBlockState(check).isSolid()) {
                surfaceY = heightmapY - dy + 1; // stand ON TOP of the solid block
                break;
            }
        }

        activeFoodGoalId = "food_surface_escape";
        goalStallTicks = 0;
        isNavigationGoal = true;
        navTargetX = px;
        navTargetY = surfaceY;
        navTargetZ = pz;
        lastDistanceToTarget = Math.sqrt(
                (player.getX() - navTargetX) * (player.getX() - navTargetX)
                + (player.getY() - navTargetY) * (player.getY() - navTargetY)
                + (player.getZ() - navTargetZ) * (player.getZ() - navTargetZ));

        JsonObject target = new JsonObject();
        target.addProperty("x", px);
        target.addProperty("y", surfaceY);
        target.addProperty("z", pz);

        float goalPriority = lastComputedScore + 1.5f;
        goalSetRef.addDynamicGoal(new GoalSet.Goal(
                activeFoodGoalId, "navigate_to", goalPriority, target));

        EmmaBridgeMod.LOGGER.info("[CollectFood] Priority 6: Underground with no food and no base, navigating to surface Y={}",
                surfaceY);

        lastFoodCount = countFoodInInventory(player);
        active = false; // yield to NavigateToAction
        phase = Phase.GOAL_CHAIN_MONITOR;
    }

    // ── Lifecycle ───────────────────────────────────────────────

    @Override
    public void onDeactivated(Minecraft client) {
        active = false;
        targetEntity = null;
        phase = Phase.EVALUATE;
        // Don't clean up food goal on deactivation — it should persist for other actions
        cancelPathing();
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public String getPrimaryGoalId() {
        return "stay_fed";
    }

    private void cancelPathing() {
        EmmatoneAPI.getProvider().getPrimaryEmmatone().getPathingBehavior().cancelEverything();
    }

    // ── Food source scanning ────────────────────────────────────

    private Entity findNearestGroundFood(LocalPlayer player) {
        AABB searchBox = player.getBoundingBox().inflate(32);
        Entity nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (Entity entity : player.level().getEntities(player, searchBox)) {
            if (entity instanceof ItemEntity itemEntity) {
                if (itemEntity.getItem().has(DataComponents.FOOD)) {
                    double dist = player.distanceTo(entity);
                    if (dist < nearestDist) {
                        nearestDist = dist;
                        nearest = entity;
                    }
                }
            }
        }
        return nearest;
    }

    private List<BlockPos> findNearbyFoodBlocks(LocalPlayer player, String fullBlockId) {
        // Use BlockScanner data from WorldState (populated by GoapTicker every second)
        if (cachedWorldState != null && !cachedWorldState.nearbyBlocks.isEmpty()) {
            List<BlockPos> positions = cachedWorldState.nearbyBlocks.get(fullBlockId);
            return positions != null ? positions : List.of();
        }
        // Fallback: no WorldState cached yet (shouldn't happen in normal operation)
        return List.of();
    }

    private Entity findNearestFoodAnimal(LocalPlayer player) {
        Set<String> foodEntities = ItemRecipeRegistry.getFoodSourceEntities();
        AABB searchBox = player.getBoundingBox().inflate(32);
        Entity nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (Entity entity : player.level().getEntities(player, searchBox)) {
            // Skip hostile mobs (hoglin drops porkchop but we don't hunt it for food)
            if (entity instanceof Monster) continue;

            String typeId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
            if (foodEntities.contains(typeId)) {
                double dist = player.distanceTo(entity);
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearest = entity;
                }
            }
        }
        return nearest;
    }

    /**
     * Find the best multi-step food item obtainable given nearby resources.
     * Returns the food item ID, or null if none feasible.
     */
    private String findBestMultiStepFood(LocalPlayer player) {
        for (var candidate : ItemRecipeRegistry.getFoodCandidatesByEfficiency()) {
            if (candidate.acquisitionSteps() < 2) continue; // direct sources handled above

            // Check if any raw materials for this food are available
            List<String> deps = ItemRecipeRegistry.getTransitiveDependencies(candidate.itemId());
            for (String dep : deps) {
                // Check if we have any dep in inventory
                String fullDep = dep.contains(":") ? dep : "minecraft:" + dep;
                if (player.getInventory().contains(
                        stack -> ItemClassifier.itemId(stack).equals(fullDep))) {
                    return candidate.itemId();
                }

                // Check if dep has mineable blocks nearby
                for (ItemRecipeEntry entry : ItemRecipeRegistry.getEntries(dep)) {
                    if (entry.getMineBlockNames() != null) {
                        for (String block : entry.getMineBlockNames()) {
                            String fullBlock = block.contains(":") ? block : "minecraft:" + block;
                            if (!findNearbyFoodBlocks(player, fullBlock).isEmpty()) {
                                return candidate.itemId();
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    // ── Utility ─────────────────────────────────────────────────

    private int countFoodInInventory(LocalPlayer player) {
        int count = InventoryScanner.countItems(player.getInventory(),
                stack -> stack.has(DataComponents.FOOD));
        // Also count food stored in endinv
        if (cachedWorldState != null) {
            for (var entry : cachedWorldState.endinvInventory.entrySet()) {
                String id = entry.getKey();
                // Check if this item is a known food via the registry
                var item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getValue(net.minecraft.resources.Identifier.parse(id));
                if (item != null) {
                    var stack = new net.minecraft.world.item.ItemStack(item);
                    if (stack.has(DataComponents.FOOD)) {
                        count += entry.getValue();
                    }
                }
            }
        }
        return count;
    }
}
