package com.emma.bridge.goap.actions;

import emmatone.api.EmmatoneAPI;
import emmatone.api.pathing.goals.GoalRunAway;
import com.emma.bridge.goap.GoapAction;
import com.emma.bridge.goap.GoalSet;
import com.emma.bridge.goap.WorldState;
import com.emma.bridge.goap.WorldState.ThreatInfo;
import com.emma.bridge.util.CombatHelper;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * GOAP Action: Flee from threats when outmatched.
 *
 * Inverse of AttackEntityAction -- scores high when:
 *   - Health is low
 *   - No weapon available
 *   - Outnumbered by hostiles
 *   - Creeper nearby
 *
 * Delegates to Emmatone's GoalRunAway for pathfinding away from threats.
 */
public class FleeFromAction extends GoapAction {

    /** Distance to maintain from threats. */
    private static final double FLEE_DISTANCE = 30.0;

    /** Health threshold where flee becomes strongly preferred. */
    private static final float LOW_HEALTH = 10.0f;

    /** Critical health -- almost always flee. */
    private static final float CRITICAL_HEALTH = 6.0f;

    private boolean fleeing = false;
    private int threatCount = 0;

    @Override
    public String getName() {
        return "FleeFrom";
    }

    @Override
    public boolean checkPreconditions(WorldState state) {
        // Only flee if at least one mob is actively targeting the player
        return state.threats.stream().anyMatch(t -> t.targetingPlayer);
    }

    @Override
    public float computeScore(WorldState state, GoalSet goals) {
        if (state.threats.isEmpty()) return 0;

        float goalPriority = goals.getGoal("survive")
                .map(g -> g.priority)
                .orElse(8.0f);

        // Count threats targeting the player within 16 blocks
        int nearThreats = 0;
        boolean creeperNearby = false;
        CombatHelper.DangerTier highestTier = CombatHelper.DangerTier.STANDARD;
        double closestDist = Double.MAX_VALUE;

        for (ThreatInfo threat : state.threats) {
            if (threat.distance < 16 && threat.targetingPlayer) {
                nearThreats++;
                if (threat.distance < closestDist) closestDist = threat.distance;
                if (threat.type.contains("creeper")) creeperNearby = true;
                CombatHelper.DangerTier tier = CombatHelper.getDangerTier(threat.type);
                if (tier.level > highestTier.level) highestTier = tier;
            }
        }

        if (nearThreats == 0) return 0;
        threatCount = nearThreats;

        // Phantoms fly faster than the player — fleeing is pointless, stand and fight
        boolean allPhantoms = state.threats.stream()
                .filter(t -> t.distance < 16)
                .allMatch(t -> t.type.contains("phantom"));
        if (allPhantoms) return 0;

        // Health factor: higher score when low health
        float healthFactor;
        if (state.health <= CRITICAL_HEALTH) {
            healthFactor = 1.0f;
        } else if (state.health <= LOW_HEALTH) {
            healthFactor = 0.7f;
        } else if (state.health > 15.0f) {
            healthFactor = 0.1f;
        } else {
            healthFactor = 0.3f;
        }

        // Status effect boost: DOT effects make current health misleading
        if (state.hasDamageOverTimeEffect()) {
            healthFactor = Math.max(healthFactor, 0.6f);
            if (state.health <= 14.0f) healthFactor = Math.max(healthFactor, 0.8f);
        }
        if (state.hasEffect("minecraft:weakness")) {
            healthFactor = Math.max(healthFactor, 0.5f);
        }
        if (state.hasEffect("minecraft:blindness")) {
            healthFactor = Math.max(healthFactor, 0.6f);
        }
        if (state.hasEffect("minecraft:slowness")) {
            healthFactor = Math.max(healthFactor, 0.4f);
        }

        // Tier-based danger scaling (replaces old isDangerousMob + health<=10 gate)
        float dangerBonus = 0f;
        switch (highestTier) {
            case EXTREME -> {
                dangerBonus = 0.8f;
                if (state.armorValue < 15 || state.bestWeaponDamage < 7.0) {
                    dangerBonus = 1.0f;
                }
            }
            case HIGH -> {
                dangerBonus = 0.4f;
                if (state.health <= LOW_HEALTH) dangerBonus = 0.7f;
            }
            case MODERATE -> {
                dangerBonus = 0.1f;
                if (state.health <= LOW_HEALTH) dangerBonus = 0.3f;
            }
            default -> {}
        }
        healthFactor = Math.max(healthFactor, dangerBonus);

        // Outnumbered factor
        float outnumberedFactor = Math.min(nearThreats / 3.0f, 1.0f);

        // Creeper bonus — fusing creeper within blast radius is an emergency
        float creeperBonus = 0.0f;
        if (creeperNearby) {
            creeperBonus = 0.4f;
        }
        // Fusing creeper detected by WorldState — massive flee urgency
        if (state.fusingCreeperDistance < 8.0f) {
            creeperBonus = 1.0f;  // max urgency — get away NOW
        }

        // Proximity urgency
        float proximityFactor = (float) (1.0 / (1.0 + closestDist / 8.0));

        // Equipment factor: worse gear = more reason to flee
        double equipScore = CombatHelper.getEquipmentScore(state, state.bestWeaponDamage);
        float gearPenalty = 1.5f - Math.min(1.0f, (float) (equipScore / 10.0));

        // Combined flee urgency
        float urgency = Math.max(healthFactor, outnumberedFactor) + creeperBonus;
        // Only cap at 1.0 if no fusing creeper — fusing creeper can push above 1.0
        if (creeperBonus <= 0.4f) {
            urgency = Math.min(urgency, 1.0f);
        }

        float score = goalPriority * urgency * proximityFactor * gearPenalty;

        // Critical health override
        if (state.health <= CRITICAL_HEALTH) {
            score *= 3.0f;
        }

        // Weapon discount: single non-creeper non-extreme threat with weapon
        if (nearThreats == 1 && !creeperNearby && state.bestWeaponDamage > 1.0f
                && highestTier.level < CombatHelper.DangerTier.EXTREME.level) {
            score *= 0.4f;
        }

        return score;
    }

    @Override
    public void execute(Minecraft client) {
        fleeing = true;
        startFleeing(client);
    }

    @Override
    public void tick(Minecraft client) {
        if (!fleeing) return;

        // Check if Emmatone is still pathing; re-issue if stopped but threats remain
        boolean pathing = EmmatoneAPI.getProvider()
                .getPrimaryEmmatone()
                .getPathingBehavior().isPathing();

        if (!pathing) {
            // Re-evaluate: if threats still nearby, keep fleeing
            startFleeing(client);
        }
    }

    @Override
    public void onDeactivated(Minecraft client) {
        if (fleeing) {
            try {
                EmmatoneAPI.getProvider()
                        .getPrimaryEmmatone()
                        .getPathingBehavior().cancelEverything();
            } catch (Exception ignored) {}
            fleeing = false;
        }
        threatCount = 0;
    }

    @Override
    public boolean isActive() {
        return fleeing;
    }

    @Override
    public int getMinimumActiveTicks() {
        // Commit to fleeing for 2 seconds — prevents oscillation when threats
        // are near the 16-block scan boundary causing score to swing 0↔12+
        return 40;
    }

    @Override
    public String getPrimaryGoalId() {
        return "survive";
    }

    @Override
    public String personalityCategory() {
        return "safety";
    }

    @Override
    public JsonObject getScoreBreakdown(WorldState state, GoalSet goals) {
        JsonObject bd = super.getScoreBreakdown(state, goals);
        bd.addProperty("fleeing", fleeing);
        bd.addProperty("health", state.health);
        bd.addProperty("threat_count", threatCount);
        bd.addProperty("low_health", state.health <= LOW_HEALTH);
        bd.addProperty("has_dot_effect", state.hasDamageOverTimeEffect());
        bd.addProperty("has_harmful_effect", state.hasHarmfulEffect());
        if (!state.threats.isEmpty()) {
            bd.addProperty("highest_danger_tier",
                    CombatHelper.getHighestDangerTier(state.threats).name());
        }
        return bd;
    }

    // -- Flee logic -------------------------------------------------------

    private void startFleeing(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) return;

        // Collect positions of hostiles targeting the player
        AABB scanBox = player.getBoundingBox().inflate(16);
        List<BlockPos> threatPositions = new ArrayList<>();

        for (Entity entity : player.level().getEntities(player, scanBox)) {
            if (entity instanceof Monster mob && entity.isAlive()) {
                // Only flee from mobs targeting us (or very close — they're about to hit)
                if (mob.getTarget() == player || player.distanceTo(entity) < 4.0) {
                    threatPositions.add(entity.blockPosition());
                }
            }
        }

        if (threatPositions.isEmpty()) {
            fleeing = false;
            return;
        }

        // Use Emmatone GoalRunAway from all threat positions
        EmmatoneAPI.getProvider()
                .getPrimaryEmmatone()
                .getCustomGoalProcess()
                .setGoalAndPath(new GoalRunAway(
                        FLEE_DISTANCE,
                        threatPositions.toArray(new BlockPos[0])));
    }
}
