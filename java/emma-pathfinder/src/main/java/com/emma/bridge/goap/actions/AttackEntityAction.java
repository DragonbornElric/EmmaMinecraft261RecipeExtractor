package com.emma.bridge.goap.actions;

import com.emma.bridge.control.BlockInteraction;
import com.emma.bridge.events.CombatLog;
import com.emma.bridge.goap.GoapAction;
import com.emma.bridge.goap.GoapStateFlags;
import com.emma.bridge.goap.GoalSet;
import com.emma.bridge.goap.StrategyKnowledge;
import com.emma.bridge.goap.WorldState;
import com.emma.bridge.util.CombatHelper;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.item.Item;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.AABB;
import emmatone.api.EmmatoneAPI;
import emmatone.api.pathing.goals.GoalNear;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * GOAP Action: Attack the nearest hostile entity.
 *
 * Preconditions: hostile within 16 blocks
 * Score: survive goal priority x threat proximity urgency
 * Collateral: none (pure combat)
 *
 * Uses CombatHelper for weapon selection, BlockInteraction for aiming,
 * and direct interactionManager for attack execution.
 * Respects attack cooldown (getAttackCooldownProgress).
 */
public class AttackEntityAction extends GoapAction {

    /** Max range for engagement (blocks). */
    private static final float ENGAGE_RANGE = 16.0f;

    /** Melee attack reach (blocks). MC server cap is ~3.0; beyond that attacks are no-ops. */
    private static final float ATTACK_REACH = 3.0f;

    private final StrategyKnowledge knowledge = new StrategyKnowledge();
    private Entity currentTarget = null;
    private boolean engaged = false;

    @Override
    public String getName() {
        return "AttackEntity";
    }

    @Override
    public boolean checkPreconditions(WorldState state) {
        if (GoapStateFlags.get().isEating) return false;
        // Note: do NOT check isShielding here — we need AttackEntity to score
        // during shield blocks so it can outbid non-combat actions
        return !state.threats.isEmpty() && state.threats.get(0).distance < ENGAGE_RANGE;
    }

    @Override
    public float computeScore(WorldState state, GoalSet goals) {
        if (state.threats.isEmpty()) return 0;

        float goalPriority = goals.getGoal("survive")
                .map(g -> g.priority)
                .orElse(8.0f);

        // Threat proximity urgency: 1.0 at melee range, drops off with distance
        float closestDist = state.threats.get(0).distance;
        float proximityUrgency = 1.0f / (1.0f + closestDist / 5.0f);

        // Health urgency: lower health = more urgent to kill threats
        float healthFactor = 1.0f + (1.0f - state.health / state.maxHealth) * 0.5f;

        // Multiple threats: more threats = more urgent
        float threatMultiplier = Math.min(1.5f, 1.0f + (state.threats.size() - 1) * 0.1f);

        // Equipment factor: better gear = more willing to fight
        // equipmentScore ranges ~0-10, normalize to 0.5-1.5 multiplier
        double equipScore = CombatHelper.getEquipmentScore(state, state.bestWeaponDamage);
        float gearFactor = 0.5f + Math.min(1.0f, (float) (equipScore / 10.0));

        float score = goalPriority * proximityUrgency * healthFactor * threatMultiplier * gearFactor;

        // Weapon bonus: having a sword/axe makes fighting more viable
        if (state.bestWeaponDamage > 3.0f) {
            score *= 1.3f;
        }

        // Single-target bonus: safe engagement against lone non-creeper threat
        if (state.threats.size() == 1 && !state.threats.get(0).type.contains("creeper")) {
            score *= 1.2f;
        }

        // Danger tier penalty: deprioritize attacking extremely dangerous mobs
        CombatHelper.DangerTier highestTier = CombatHelper.getHighestDangerTier(state.threats);
        switch (highestTier) {
            case EXTREME -> {
                // Warden/Wither: heavily penalize unless godly gear
                if (equipScore < 12.0) {
                    score *= 0.1f;
                } else {
                    score *= 0.5f;
                }
            }
            case HIGH -> {
                // Wither skeletons, hoglins etc.: penalize when poorly geared
                if (equipScore < 6.0) {
                    score *= 0.5f;
                }
            }
            default -> {}
        }

        // Status effect penalties
        if (state.hasEffect("minecraft:weakness")) {
            score *= 0.5f;
        }
        if (state.hasEffect("minecraft:blindness")) {
            score *= 0.3f;
        }
        if (state.hasEffect("minecraft:mining_fatigue")) {
            int amplifier = state.getEffectAmplifier("minecraft:mining_fatigue");
            score *= Math.max(0.3f, 1.0f - (amplifier + 1) * 0.2f);
        }

        return score;
    }

    @Override
    public void execute(Minecraft client) {
        engaged = true;
        // Equip best weapon
        if (client.player != null) {
            CombatHelper.equipBestWeapon(client.player);
        }
    }

    @Override
    public void tick(Minecraft client) {
        if (!engaged) return;

        LocalPlayer player = client.player;
        if (player == null) return;

        // Find nearest hostile entity
        currentTarget = findNearestHostile(client);
        if (currentTarget == null) return;

        // Look at target
        BlockInteraction.lookAt(currentTarget.getEyePosition());

        // Equip best weapon (in case inventory changed)
        CombatHelper.equipBestWeapon(player);

        float dist = player.distanceTo(currentTarget);

        if (dist <= ATTACK_REACH) {
            // In melee range — attack when cooldown ready
            if (CombatHelper.tryAttack(client, player, currentTarget)) {
                // Record to CombatLog
                JsonObject hitData = new JsonObject();
                hitData.addProperty("target_type", currentTarget.getType().getDescriptionId());
                if (currentTarget instanceof LivingEntity living) {
                    hitData.addProperty("target_health", living.getHealth());
                }
                hitData.addProperty("distance", dist);
                Item weapon = player.getMainHandItem().getItem();
                hitData.addProperty("weapon", BuiltInRegistries.ITEM.getKey(weapon).toString());
                hitData.addProperty("source", "goap");
                CombatLog.getInstance().record("damage_dealt", hitData);
            }
        } else if (dist <= ENGAGE_RANGE) {
            // Phantoms are airborne — don't pathfind, just face and wait for swoop
            if (currentTarget instanceof Phantom) return;

            // Out of melee range — approach the target
            var emmatone = EmmatoneAPI.getProvider().getPrimaryEmmatone();
            if (!emmatone.getPathingBehavior().isPathing()) {
                BlockPos targetPos = currentTarget.blockPosition();
                emmatone.getCustomGoalProcess().setGoalAndPath(
                        new GoalNear(targetPos, (int) ATTACK_REACH));
            }
        }
    }

    @Override
    public void onDeactivated(Minecraft client) {
        engaged = false;
        currentTarget = null;
        // Stop chasing when combat ends
        EmmatoneAPI.getProvider().getPrimaryEmmatone().getPathingBehavior().cancelEverything();
    }

    @Override
    public boolean isActive() {
        return engaged;
    }

    // -- Collateral + personality -----------------------------------------

    @Override
    public String getPrimaryGoalId() {
        return "survive";
    }

    @Override
    public float relevanceToGoal(WorldState state, GoalSet.Goal goal) {
        return 0.0f;  // pure combat, no collateral benefit
    }

    @Override
    public String personalityCategory() {
        return "aggression";
    }

    @Override
    public StrategyKnowledge getStrategyKnowledge() {
        return knowledge;
    }

    // -- Debug ------------------------------------------------------------

    @Override
    public JsonObject getScoreBreakdown(WorldState state, GoalSet goals) {
        JsonObject bd = super.getScoreBreakdown(state, goals);
        bd.addProperty("threat_count", state.threats.size());
        if (!state.threats.isEmpty()) {
            var closest = state.threats.get(0);
            bd.addProperty("closest_type", closest.type);
            bd.addProperty("closest_dist", closest.distance);
            bd.addProperty("closest_health", closest.health);
            bd.addProperty("highest_danger_tier",
                    CombatHelper.getHighestDangerTier(state.threats).name());
        }
        bd.addProperty("engaged", engaged);
        bd.addProperty("target", currentTarget != null
                ? currentTarget.getType().getDescriptionId() : "none");
        bd.addProperty("has_weakness", state.hasEffect("minecraft:weakness"));
        bd.addProperty("has_blindness", state.hasEffect("minecraft:blindness"));
        return bd;
    }

    // -- Entity scanning --------------------------------------------------

    private Entity findNearestHostile(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || player.level() == null) return null;

        Entity nearest = null;
        double nearestDist = Double.MAX_VALUE;

        AABB scanBox = player.getBoundingBox().inflate(ENGAGE_RANGE);
        List<Entity> entities = player.level().getEntities(player, scanBox);

        for (Entity entity : entities) {
            if (entity instanceof Monster monster && entity.isAlive()) {
                // Skip neutral endermen — don't provoke them
                if (monster instanceof EnderMan enderMan && !enderMan.isCreepy()) continue;
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
