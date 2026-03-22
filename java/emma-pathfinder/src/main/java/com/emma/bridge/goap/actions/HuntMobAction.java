package com.emma.bridge.goap.actions;

import emmatone.api.EmmatoneAPI;
import emmatone.api.pathing.goals.GoalNear;
import com.emma.bridge.EmmaBridgeMod;
import com.emma.bridge.control.BlockInteraction;
import com.emma.bridge.goap.GoapAction;
import com.emma.bridge.goap.GoalSet;
import com.emma.bridge.goap.WorldState;
import com.emma.bridge.util.CombatHelper;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.*;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.cow.MushroomCow;
import net.minecraft.world.entity.animal.equine.Donkey;
import net.minecraft.world.entity.animal.equine.Horse;
import net.minecraft.world.entity.animal.equine.Llama;
import net.minecraft.world.entity.animal.fish.Cod;
import net.minecraft.world.entity.animal.fish.Salmon;
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.rabbit.Rabbit;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.animal.squid.GlowSquid;
import net.minecraft.world.entity.animal.squid.Squid;
import net.minecraft.world.entity.animal.turtle.Turtle;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Map;

/**
 * GOAP Action: Hunt specific mob types for item drops.
 *
 * Scores on derived goals with type "hunt_mob" (created by GoalDecomposer
 * for MOB_DROP items). Finds the target mob class within 32 blocks,
 * paths via Emmatone, attacks on cooldown.
 *
 * Example: goal "derived_beef" (hunt_mob, mob_class=Cow) → find cow,
 * path to it, kill it. Raw beef drops, inventory changes, SmeltItem takes over.
 *
 * Personality: aggression (active hunting, not passive collection).
 */
public class HuntMobAction extends GoapAction {

    private static final float SEARCH_RANGE = 32.0f;
    private static final float ATTACK_REACH = 3.5f;
    private static final int NAV_TIMEOUT_TICKS = 400;  // 20 seconds

    /** Map mob class names (lowercase, matching item_recipes.json) to entity classes. */
    private static final Map<String, Class<? extends Entity>> MOB_CLASSES = Map.ofEntries(
            // Passive mobs
            Map.entry("cow", Cow.class),
            Map.entry("pig", Pig.class),
            Map.entry("sheep", Sheep.class),
            Map.entry("chicken", Chicken.class),
            Map.entry("rabbit", Rabbit.class),
            Map.entry("mooshroom", MushroomCow.class),
            Map.entry("goat", Goat.class),
            Map.entry("horse", Horse.class),
            Map.entry("donkey", Donkey.class),
            Map.entry("llama", Llama.class),
            Map.entry("turtle", Turtle.class),
            Map.entry("squid", Squid.class),
            Map.entry("glow_squid", GlowSquid.class),
            Map.entry("cod", Cod.class),
            Map.entry("salmon", Salmon.class),
            // Hostile mobs — needed for kill_dragon chain (blaze_rod, ender_pearl)
            Map.entry("blaze", Blaze.class),
            Map.entry("enderman", EnderMan.class)
    );

    private boolean active = false;
    private Entity targetMob = null;
    private String targetMobClass = null;
    private String targetGoalId = null;
    private int navTicks = 0;

    @Override
    public String getName() {
        return "HuntMob";
    }

    @Override
    public String personalityCategory() {
        return "aggression";
    }

    @Override
    public boolean checkPreconditions(WorldState state) {
        return true;  // scoring determines viability
    }

    @Override
    public float computeScore(WorldState state, GoalSet goals) {
        float bestScore = 0;

        for (GoalSet.Goal goal : goals.getGoals()) {
            if (!"hunt_mob".equals(goal.type)) continue;
            if (goal.target == null || !goal.target.has("mob_class")) continue;

            String mobClass = goal.target.get("mob_class").getAsString();

            // Check if target mob exists nearby
            Class<? extends Entity> entityClass = MOB_CLASSES.get(mobClass);
            if (entityClass == null) continue;

            Minecraft client = Minecraft.getInstance();
            if (client.player == null) continue;

            Entity nearest = findNearestMob(client.player, entityClass);
            if (nearest == null) continue;

            float distance = client.player.distanceTo(nearest);
            float proximityFactor = 1.0f / (1.0f + distance / 10.0f);
            float score = goal.priority * proximityFactor;

            if (score > bestScore) {
                bestScore = score;
                targetMobClass = mobClass;
                targetGoalId = goal.id;
            }
        }

        return bestScore;
    }

    @Override
    public void execute(Minecraft client) {
        active = true;
        navTicks = 0;
        targetMob = null;

        if (client.player != null) {
            CombatHelper.equipBestWeapon(client.player);
        }

        EmmaBridgeMod.LOGGER.info("[GOAP HuntMob] Hunting {} for goal {}",
                targetMobClass, targetGoalId);
    }

    @Override
    public void tick(Minecraft client) {
        if (!active) return;

        LocalPlayer player = client.player;
        if (player == null) return;

        // Check if target is dead or gone
        if (targetMob != null && (targetMob.isRemoved() || !targetMob.isAlive())) {
            targetMob = null;
            navTicks = 0;
        }

        // Find new target if needed
        if (targetMob == null) {
            Class<? extends Entity> entityClass = MOB_CLASSES.get(targetMobClass);
            if (entityClass != null) {
                targetMob = findNearestMob(player, entityClass);
            }

            if (targetMob == null) {
                // No mob found — deactivate and let scorer try something else
                navTicks++;
                if (navTicks > NAV_TIMEOUT_TICKS) {
                    EmmaBridgeMod.LOGGER.info("[GOAP HuntMob] No {} found after timeout", targetMobClass);
                    active = false;
                }
                return;
            }
        }

        float distance = player.distanceTo(targetMob);

        if (distance <= ATTACK_REACH) {
            // In range — attack on cooldown
            EmmatoneAPI.getProvider().getPrimaryEmmatone().getPathingBehavior().cancelEverything();

            BlockInteraction.lookAt(targetMob.getEyePosition());

            CombatHelper.tryAttack(client, player, targetMob);
        } else {
            // Out of range — path to mob
            if (!EmmatoneAPI.getProvider().getPrimaryEmmatone().getPathingBehavior().isPathing()) {
                EmmatoneAPI.getProvider().getPrimaryEmmatone().getCustomGoalProcess()
                        .setGoalAndPath(new GoalNear(targetMob.blockPosition(), 2));
            }

            navTicks++;
            if (navTicks > NAV_TIMEOUT_TICKS) {
                EmmaBridgeMod.LOGGER.info("[GOAP HuntMob] Navigation timeout, retargeting");
                targetMob = null;
                navTicks = 0;
            }
        }
    }

    @Override
    public void onDeactivated(Minecraft client) {
        active = false;
        targetMob = null;
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
    public float relevanceToGoal(WorldState state, GoalSet.Goal goal) {
        // If we're hunting beef and there's a cooked_beef goal, we're relevant
        if (targetMobClass == null) return 0.0f;
        if (goal.target == null || !goal.target.has("item")) return 0.0f;
        // Delegate to parent goal relevance — we produce a raw material
        return 0.3f;
    }

    @Override
    public JsonObject getScoreBreakdown(WorldState state, GoalSet goals) {
        JsonObject bd = super.getScoreBreakdown(state, goals);
        bd.addProperty("target_mob_class", targetMobClass != null ? targetMobClass : "none");
        bd.addProperty("target_goal", targetGoalId != null ? targetGoalId : "none");
        bd.addProperty("has_target", targetMob != null);
        bd.addProperty("active", active);
        return bd;
    }

    // ── Helpers ──────────────────────────────────────────────────

    private Entity findNearestMob(LocalPlayer player, Class<? extends Entity> entityClass) {
        AABB searchBox = player.getBoundingBox().inflate(SEARCH_RANGE);
        Entity nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (Entity entity : player.level().getEntities(player, searchBox)) {
            if (entityClass.isInstance(entity) && entity.isAlive()) {
                double dist = player.distanceToSqr(entity);
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearest = entity;
                }
            }
        }
        return nearest;
    }
}
