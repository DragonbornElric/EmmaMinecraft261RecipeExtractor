package com.emma.bridge.goap.actions;

import emmatone.api.EmmatoneAPI;
import emmatone.api.pathing.goals.GoalNear;
import emmatone.api.pathing.goals.GoalXZ;
import com.emma.bridge.EmmaBridgeMod;
import com.emma.bridge.control.BlockInteraction;
import com.emma.bridge.events.CombatLog;
import com.emma.bridge.goap.GoapAction;
import com.emma.bridge.goap.GoapStateFlags;
import com.emma.bridge.goap.GoalSet;
import com.emma.bridge.goap.WorldState;
import com.emma.bridge.util.CombatHelper;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.item.Item;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * GOAP Action: Proactively hunt hostile mobs.
 *
 * Unlike AttackEntityAction (reactive — only scores when threats are within 16 blocks),
 * this action is proactive: it scores against "hunt_hostile" goals and actively patrols
 * with a 32-block scan radius, navigating toward hostiles before they become threats.
 *
 * Ported from the old AltoClef HeroTask loop:
 *   scan all loaded entities for hostile/slime → navigate → attack → loot → patrol if none
 *
 * Personality: aggression
 */
public class HuntHostileAction extends GoapAction {

    /** Extended scan radius (larger than AttackEntity's 16). */
    private static final float HUNT_RANGE = 32.0f;

    /** Melee attack reach. */
    private static final float ATTACK_REACH = 3.5f;

    /** How far to patrol when no hostiles are found. */
    private static final int PATROL_DISTANCE = 32;

    /** Max ticks navigating before retargeting. */
    private static final int NAV_TIMEOUT_TICKS = 400;  // 20 seconds

    private boolean active = false;
    private Entity currentTarget = null;
    private String targetGoalId = null;
    private int navTicks = 0;
    private boolean patrolling = false;

    @Override
    public String getName() {
        return "HuntHostile";
    }

    @Override
    public String personalityCategory() {
        return "aggression";
    }

    @Override
    public boolean checkPreconditions(WorldState state) {
        if (GoapStateFlags.get().isEating) return false;
        return true;  // scoring determines viability via goal matching
    }

    @Override
    public float computeScore(WorldState state, GoalSet goals) {
        // Only score if a hunt_hostile goal exists
        float goalPriority = 0;
        String bestGoalId = null;

        for (GoalSet.Goal goal : goals.getGoals()) {
            if (!"hunt_hostile".equals(goal.type)) continue;
            if (goal.priority > goalPriority) {
                goalPriority = goal.priority;
                bestGoalId = goal.id;
            }
        }

        if (goalPriority == 0) return 0;

        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return 0;

        // Find nearest hostile in extended range
        Entity target = findNearestHostile(client.player, HUNT_RANGE);

        if (target != null) {
            float distance = client.player.distanceTo(target);
            float proximityFactor = 1.0f / (1.0f + distance / 10.0f);

            // Equipment factor: better gear = more willing to fight
            double equipScore = CombatHelper.getEquipmentScore(state, state.bestWeaponDamage);
            float gearFactor = 0.5f + Math.min(1.0f, (float) (equipScore / 10.0));

            // Health factor: don't hunt when low
            float healthFactor = Math.min(1.0f, state.health / (state.maxHealth * 0.5f));

            // Weapon bonus
            float weaponBonus = state.bestWeaponDamage > 3.0f ? 1.2f : 1.0f;

            // Danger tier penalty for extremely dangerous mobs
            if (target instanceof LivingEntity) {
                CombatHelper.DangerTier tier = CombatHelper.getDangerTier(
                        target.getType().getDescriptionId());
                switch (tier) {
                    case EXTREME -> {
                        if (equipScore < 12.0) return 0;  // Don't hunt wardens/withers without gear
                        gearFactor *= 0.5f;
                    }
                    case HIGH -> {
                        if (equipScore < 6.0) gearFactor *= 0.5f;
                    }
                    default -> {}
                }
            }

            float score = goalPriority * proximityFactor * gearFactor * healthFactor * weaponBonus;

            targetGoalId = bestGoalId;
            return score;
        }

        // No hostile found — still score low to trigger patrol/wander
        // Low score = loses to mining/crafting but wins over idle
        targetGoalId = bestGoalId;
        return goalPriority * 0.15f;
    }

    @Override
    public void execute(Minecraft client) {
        active = true;
        navTicks = 0;
        currentTarget = null;
        patrolling = false;

        if (client.player != null) {
            CombatHelper.equipBestWeapon(client.player);
        }

        EmmaBridgeMod.LOGGER.info("[GOAP HuntHostile] Activated for goal {}", targetGoalId);
    }

    @Override
    public void tick(Minecraft client) {
        if (!active) return;

        LocalPlayer player = client.player;
        if (player == null) return;

        // Check if current target is dead or gone
        if (currentTarget != null && (currentTarget.isRemoved() || !currentTarget.isAlive())) {
            currentTarget = null;
            navTicks = 0;
            patrolling = false;
        }

        // Scan for nearest hostile
        Entity nearest = findNearestHostile(player, HUNT_RANGE);

        // If we found a target (possibly new, possibly existing)
        if (nearest != null) {
            if (currentTarget != nearest) {
                // New target — reset nav
                currentTarget = nearest;
                navTicks = 0;
                patrolling = false;
            }

            float distance = player.distanceTo(currentTarget);

            if (distance <= ATTACK_REACH) {
                // In melee range — attack
                var emmatone = EmmatoneAPI.getProvider().getPrimaryEmmatone();
                emmatone.getPathingBehavior().cancelEverything();

                BlockInteraction.lookAt(currentTarget.getEyePosition());
                CombatHelper.equipBestWeapon(player);

                if (CombatHelper.tryAttack(client, player, currentTarget)) {
                    // Record to CombatLog
                    JsonObject hitData = new JsonObject();
                    hitData.addProperty("target_type", currentTarget.getType().getDescriptionId());
                    if (currentTarget instanceof LivingEntity living) {
                        hitData.addProperty("target_health", living.getHealth());
                    }
                    hitData.addProperty("distance", distance);
                    Item weapon = player.getMainHandItem().getItem();
                    hitData.addProperty("weapon", BuiltInRegistries.ITEM.getKey(weapon).toString());
                    hitData.addProperty("source", "goap_hunt_hostile");
                    CombatLog.getInstance().record("damage_dealt", hitData);
                }
            } else {
                // Phantoms are airborne — don't pathfind, just face and wait for swoop
                if (currentTarget instanceof Phantom) {
                    BlockInteraction.lookAt(currentTarget.getEyePosition());
                    return;
                }

                // Navigate to target via Emmatone
                var emmatone = EmmatoneAPI.getProvider().getPrimaryEmmatone();
                if (!emmatone.getPathingBehavior().isPathing()) {
                    emmatone.getCustomGoalProcess().setGoalAndPath(
                            new GoalNear(currentTarget.blockPosition(), 2));
                }

                navTicks++;
                if (navTicks > NAV_TIMEOUT_TICKS) {
                    EmmaBridgeMod.LOGGER.info("[GOAP HuntHostile] Navigation timeout, retargeting");
                    currentTarget = null;
                    navTicks = 0;
                }
            }
        } else {
            // No hostiles found — patrol
            doPatrol(player);
        }
    }

    @Override
    public void onDeactivated(Minecraft client) {
        active = false;
        currentTarget = null;
        navTicks = 0;
        patrolling = false;
        EmmatoneAPI.getProvider().getPrimaryEmmatone().getPathingBehavior().cancelEverything();
    }

    @Override
    public boolean isActive() {
        return active;
    }

    // -- Collateral + goal tracking ---------------------------------------

    @Override
    public String getPrimaryGoalId() {
        return targetGoalId;
    }

    @Override
    public float relevanceToGoal(WorldState state, GoalSet.Goal goal) {
        // Hunting hostiles contributes to survival
        if ("survive".equals(goal.id)) return 0.2f;
        return 0.0f;
    }

    // -- Debug ------------------------------------------------------------

    @Override
    public JsonObject getScoreBreakdown(WorldState state, GoalSet goals) {
        JsonObject bd = super.getScoreBreakdown(state, goals);
        bd.addProperty("target_goal", targetGoalId != null ? targetGoalId : "none");
        bd.addProperty("has_target", currentTarget != null);
        bd.addProperty("patrolling", patrolling);
        bd.addProperty("active", active);
        if (currentTarget != null) {
            bd.addProperty("target_type", currentTarget.getType().getDescriptionId());
            bd.addProperty("target_dist", Minecraft.getInstance().player != null
                    ? Minecraft.getInstance().player.distanceTo(currentTarget) : -1);
        }
        return bd;
    }

    // -- Helpers ----------------------------------------------------------

    /**
     * Find nearest hostile (Monster or Slime) within range.
     * Skips neutral Endermen (not provoked).
     */
    private Entity findNearestHostile(LocalPlayer player, float range) {
        if (player.level() == null) return null;

        AABB searchBox = player.getBoundingBox().inflate(range);
        Entity nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (Entity entity : player.level().getEntities(player, searchBox)) {
            if (!entity.isAlive()) continue;

            // Target Monster subclass (zombies, skeletons, creepers, etc.)
            // and Slime/MagmaCube (which extend Slime, not Monster)
            if (entity instanceof Monster monster) {
                // Skip neutral endermen — don't provoke them
                if (monster instanceof EnderMan enderMan && !enderMan.isCreepy()) continue;

                double dist = player.distanceToSqr(entity);
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearest = entity;
                }
            } else if (entity instanceof Slime) {
                double dist = player.distanceToSqr(entity);
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearest = entity;
                }
            }
        }

        return nearest;
    }

    /**
     * Patrol by picking a random direction and walking toward it.
     * Used when no hostiles are found to keep searching.
     */
    private void doPatrol(LocalPlayer player) {
        var emmatone = EmmatoneAPI.getProvider().getPrimaryEmmatone();

        if (!emmatone.getPathingBehavior().isPathing()) {
            if (!patrolling || navTicks > NAV_TIMEOUT_TICKS) {
                // Pick a random patrol point
                ThreadLocalRandom rng = ThreadLocalRandom.current();
                int targetX = player.blockPosition().getX() + rng.nextInt(-PATROL_DISTANCE, PATROL_DISTANCE + 1);
                int targetZ = player.blockPosition().getZ() + rng.nextInt(-PATROL_DISTANCE, PATROL_DISTANCE + 1);

                emmatone.getCustomGoalProcess().setGoalAndPath(new GoalXZ(targetX, targetZ));
                patrolling = true;
                navTicks = 0;

                EmmaBridgeMod.LOGGER.debug("[GOAP HuntHostile] Patrolling to ({}, {})", targetX, targetZ);
            }
        }

        navTicks++;
    }
}
