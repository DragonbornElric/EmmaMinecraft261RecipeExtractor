package com.emma.bridge.goap.actions;

import emmatone.api.EmmatoneAPI;
import emmatone.api.pathing.goals.GoalNear;
import com.emma.bridge.EmmaBridgeMod;
import com.emma.bridge.goap.GoapAction;
import com.emma.bridge.goap.GoalDecomposer;
import com.emma.bridge.goap.GoalSet;
import com.emma.bridge.goap.WorldState;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * GOAP Action: Pick up dropped items that match active goals.
 *
 * Opportunistic shortcut — if a diamond_pickaxe is on the ground and we have
 * a have_item_diamond_pickaxe goal, skip the entire craft chain and grab it.
 *
 * Scoring uses chain shortcut bonus: items that satisfy higher-level goals
 * (skipping more chain steps) score dramatically higher than raw materials.
 *
 * Formula: goal.priority * proximity_factor * chain_shortcut_bonus
 *   proximity_factor = 1.0 / (1.0 + distance / 10.0)
 *   chain_shortcut_bonus = steps_saved / total_chain_depth (clamped 0.1-1.0)
 *
 * Personality: exploration (opportunistic collection).
 */
public class PickupItemAction extends GoapAction {

    private static final float SEARCH_RANGE = 32.0f;
    private static final int NAV_TIMEOUT_TICKS = 200;  // 10 seconds
    private static final float MIN_SHORTCUT_BONUS = 0.1f;

    private boolean active = false;
    private ItemEntity targetItem = null;
    private String targetGoalId = null;
    private String targetItemId = null;
    private int navTicks = 0;

    @Override
    public String getName() {
        return "PickupItem";
    }

    @Override
    public String personalityCategory() {
        return "exploration";
    }

    @Override
    public boolean checkPreconditions(WorldState state) {
        return true;  // scoring determines viability
    }

    @Override
    public float computeScore(WorldState state, GoalSet goals) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || player.level() == null) return 0;

        float bestScore = 0;

        // Scan for dropped items
        AABB searchBox = player.getBoundingBox().inflate(SEARCH_RANGE);
        List<Entity> entities = player.level().getEntities(player, searchBox);

        for (Entity entity : entities) {
            if (!(entity instanceof ItemEntity itemEntity)) continue;
            if (!itemEntity.isAlive()) continue;

            String droppedId = BuiltInRegistries.ITEM.getKey(itemEntity.getItem().getItem()).toString();

            // Check against all have_item goals (user + derived)
            for (GoalSet.Goal goal : goals.getGoals()) {
                if (goal.target == null || !goal.target.has("item")) continue;
                String goalItem = goal.target.get("item").getAsString();

                if (!droppedId.equals(goalItem)) continue;

                int goalCount = goal.target.has("count") ? goal.target.get("count").getAsInt() : 1;
                if (state.hasItem(goalItem, goalCount)) continue;

                float distance = player.distanceTo(itemEntity);
                float proximityFactor = 1.0f / (1.0f + distance / 10.0f);

                // Chain shortcut bonus: how much of the chain does this skip?
                float shortcutBonus = computeShortcutBonus(droppedId, goals);

                float score = goal.priority * proximityFactor * shortcutBonus;

                if (score > bestScore) {
                    bestScore = score;
                    targetItem = itemEntity;
                    targetGoalId = goal.id;
                    targetItemId = droppedId;
                }
            }
        }

        return bestScore;
    }

    /**
     * Compute shortcut bonus for picking up an item.
     * Items that satisfy top-level goals get bonus ~1.0.
     * Items that are just raw materials get bonus ~0.1.
     */
    private float computeShortcutBonus(String itemId, GoalSet goals) {
        float bestBonus = MIN_SHORTCUT_BONUS;

        // Check each top-level user goal
        for (GoalSet.Goal goal : goals.getGoals()) {
            if (goal.isDerived()) continue;
            if (goal.target == null || !goal.target.has("item")) continue;

            String goalItem = goal.target.get("item").getAsString();

            // Direct match — picking up the goal item itself
            if (itemId.equals(goalItem)) return 1.0f;

            // Partial chain match — compute steps saved
            int totalDepth = GoalDecomposer.getChainDepth(goalItem);
            if (totalDepth <= 0) continue;

            int stepsSaved = GoalDecomposer.computeStepsSaved(itemId, goalItem);
            if (stepsSaved <= 0) continue;

            float bonus = (float) stepsSaved / (float) (totalDepth + 1);  // +1 for goal itself
            bonus = Math.max(MIN_SHORTCUT_BONUS, Math.min(1.0f, bonus));

            if (bonus > bestBonus) {
                bestBonus = bonus;
            }
        }

        return bestBonus;
    }

    @Override
    public void execute(Minecraft client) {
        active = true;
        navTicks = 0;
        EmmaBridgeMod.LOGGER.info("[GOAP PickupItem] Picking up {} for goal {}",
                targetItemId, targetGoalId);
    }

    @Override
    public void tick(Minecraft client) {
        if (!active) return;

        LocalPlayer player = client.player;
        if (player == null) return;

        // Check if target item is gone (picked up or despawned)
        if (targetItem == null || targetItem.isRemoved() || !targetItem.isAlive()) {
            active = false;
            EmmatoneAPI.getProvider().getPrimaryEmmatone().getPathingBehavior().cancelEverything();
            return;
        }

        // Auto-pickup happens when within ~1.5 blocks — just need to walk there
        float distance = player.distanceTo(targetItem);
        if (distance < 2.0f) {
            // Close enough — auto-pickup should handle it
            EmmatoneAPI.getProvider().getPrimaryEmmatone().getPathingBehavior().cancelEverything();
            // Wait a few ticks for auto-pickup
            navTicks++;
            if (navTicks > 20) {
                // Item didn't get picked up — maybe inventory full
                active = false;
            }
            return;
        }

        // Path to item
        if (!EmmatoneAPI.getProvider().getPrimaryEmmatone().getPathingBehavior().isPathing()) {
            EmmatoneAPI.getProvider().getPrimaryEmmatone().getCustomGoalProcess()
                    .setGoalAndPath(new GoalNear(targetItem.blockPosition(), 1));
        }

        navTicks++;
        if (navTicks > NAV_TIMEOUT_TICKS) {
            EmmaBridgeMod.LOGGER.info("[GOAP PickupItem] Navigation timeout for {}", targetItemId);
            active = false;
            EmmatoneAPI.getProvider().getPrimaryEmmatone().getPathingBehavior().cancelEverything();
        }
    }

    @Override
    public void onDeactivated(Minecraft client) {
        active = false;
        targetItem = null;
        navTicks = 0;
        EmmatoneAPI.getProvider().getPrimaryEmmatone().getPathingBehavior().cancelEverything();
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public String getPrimaryGoalId() {
        return targetGoalId;
    }

    @Override
    public JsonObject getScoreBreakdown(WorldState state, GoalSet goals) {
        JsonObject bd = super.getScoreBreakdown(state, goals);
        bd.addProperty("target_item", targetItemId != null ? targetItemId : "none");
        bd.addProperty("target_goal", targetGoalId != null ? targetGoalId : "none");
        bd.addProperty("has_target", targetItem != null);
        bd.addProperty("active", active);
        return bd;
    }
}
