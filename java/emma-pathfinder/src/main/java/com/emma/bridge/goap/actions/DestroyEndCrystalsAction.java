package com.emma.bridge.goap.actions;

import com.emma.bridge.EmmaBridgeMod;
import com.emma.bridge.control.BlockInteraction;
import com.emma.bridge.goap.GoapAction;
import com.emma.bridge.goap.GoalSet;
import com.emma.bridge.goap.WorldState;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * GOAP Action: Destroy end crystals on obsidian pillars in the End.
 *
 * Finds the nearest crystal, navigates to its pillar base, and attacks it.
 * Crystals explode on death (6 damage, radius 6) so the action backs away
 * after hitting. Iron bar cages are not specially handled — the explosion
 * from an adjacent crystal or direct melee through bars works in practice.
 *
 * Scores on "destroy_end_crystals" goal type. Returns 0 when no crystals remain.
 */
public class DestroyEndCrystalsAction extends GoapAction {

    private enum Phase {
        SCAN, NAVIGATE, ATTACK, RETREAT, DONE
    }

    private static final double ATTACK_RANGE = 4.5;
    private static final double SAFE_DISTANCE = 8.0;

    private Phase phase = Phase.DONE;
    private String targetGoalId;
    private int waitTicks;

    // Current target
    private int targetCrystalId = -1;
    private BlockPos targetPillarBase;
    private int crystalsDestroyed;

    @Override
    public String getName() {
        return "DestroyEndCrystals";
    }

    @Override
    public boolean checkPreconditions(WorldState state) {
        return state.dimension.contains("the_end") && state.endCrystalCount > 0;
    }

    @Override
    public float computeScore(WorldState state, GoalSet goals) {
        if (state.endCrystalCount == 0) return 0;

        for (GoalSet.Goal goal : goals.getGoals()) {
            if ("destroy_end_crystals".equals(goal.type)) {
                targetGoalId = goal.id;
                // Higher urgency with fewer crystals remaining (momentum)
                return goal.priority * 0.95f;
            }
        }
        return 0;
    }

    @Override
    public void execute(Minecraft client) {
        phase = Phase.SCAN;
        targetCrystalId = -1;
        targetPillarBase = null;
        waitTicks = 0;
        crystalsDestroyed = 0;
        EmmaBridgeMod.LOGGER.info("DestroyEndCrystals: starting crystal destruction");
    }

    @Override
    public void tick(Minecraft client) {
        LocalPlayer player = client.player;
        ClientLevel world = client.level;
        if (player == null || world == null) return;
        waitTicks++;

        switch (phase) {
            case SCAN -> tickScan(player, world);
            case NAVIGATE -> tickNavigate(player, world);
            case ATTACK -> tickAttack(player, world);
            case RETREAT -> tickRetreat(player, world);
            case DONE -> {}
        }
    }

    // ── Find nearest crystal ──────────────────────────────────────

    private void tickScan(LocalPlayer player, ClientLevel world) {
        EndCrystal nearest = null;
        double nearestDist = Double.MAX_VALUE;

        AABB scanBox = new AABB(-200, 0, -200, 200, 256, 200);
        for (Entity entity : world.getEntities(player, scanBox)) {
            if (entity instanceof EndCrystal crystal) {
                double dist = player.distanceTo(crystal);
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearest = crystal;
                }
            }
        }

        if (nearest == null) {
            EmmaBridgeMod.LOGGER.info("DestroyEndCrystals: no crystals found, done ({} destroyed)", crystalsDestroyed);
            phase = Phase.DONE;
            return;
        }

        targetCrystalId = nearest.getId();
        // Navigate to base of the pillar (crystal Y - some offset, on the ground)
        targetPillarBase = new BlockPos(
                (int) nearest.getX(),
                62, // End island surface level
                (int) nearest.getZ());
        phase = Phase.NAVIGATE;
        waitTicks = 0;
        EmmaBridgeMod.LOGGER.info("DestroyEndCrystals: targeting crystal at ({}, {}, {})",
                (int) nearest.getX(), (int) nearest.getY(), (int) nearest.getZ());
    }

    // ── Navigate to crystal ───────────────────────────────────────

    private void tickNavigate(LocalPlayer player, ClientLevel world) {
        // Check if crystal still exists
        Entity target = world.getEntity(targetCrystalId);
        if (!(target instanceof EndCrystal)) {
            // Crystal destroyed (by dragon or explosion chain)
            crystalsDestroyed++;
            phase = Phase.SCAN;
            waitTicks = 0;
            return;
        }

        double dist = player.distanceTo(target);

        if (dist < ATTACK_RANGE) {
            GoapNavHelper.cancelPathing();
            phase = Phase.ATTACK;
            waitTicks = 0;
            return;
        }

        GoapNavHelper.NavResult result = GoapNavHelper.tickNavigateToBlock(
                player, targetPillarBase, waitTicks, 400, ATTACK_RANGE);

        switch (result) {
            case ARRIVED -> {
                phase = Phase.ATTACK;
                waitTicks = 0;
            }
            case TIMEOUT -> {
                // Crystal might be on a tall pillar — try attacking from here
                phase = Phase.ATTACK;
                waitTicks = 0;
            }
            case NO_TARGET -> {
                phase = Phase.SCAN;
                waitTicks = 0;
            }
            case PATHING -> {}
        }
    }

    // ── Attack the crystal ────────────────────────────────────────

    private void tickAttack(LocalPlayer player, ClientLevel world) {
        Entity target = world.getEntity(targetCrystalId);
        if (!(target instanceof EndCrystal crystal)) {
            crystalsDestroyed++;
            phase = Phase.SCAN;
            waitTicks = 0;
            EmmaBridgeMod.LOGGER.info("DestroyEndCrystals: crystal destroyed! ({} total)", crystalsDestroyed);
            return;
        }

        double dist = player.distanceTo(crystal);

        if (dist > ATTACK_RANGE + 2) {
            // Moved too far — go back to navigate
            phase = Phase.NAVIGATE;
            waitTicks = 0;
            return;
        }

        // Look at crystal and attack
        BlockInteraction.lookAt(Vec3.atCenterOf(crystal.blockPosition()));

        // Attack the entity
        Minecraft.getInstance().gameMode.attack(player, crystal);
        player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);

        // Back away from explosion
        phase = Phase.RETREAT;
        waitTicks = 0;
    }

    // ── Retreat from explosion ─────────────────────────────────────

    private void tickRetreat(LocalPlayer player, ClientLevel world) {
        if (waitTicks > 20) {
            // Explosion over, scan for next crystal
            phase = Phase.SCAN;
            waitTicks = 0;
            return;
        }

        // Move away from crystal position
        if (targetPillarBase != null && waitTicks < 10) {
            double dx = player.getX() - targetPillarBase.getX();
            double dz = player.getZ() - targetPillarBase.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);

            if (dist < SAFE_DISTANCE) {
                // Run away from crystal
                Vec3 awayDir = new Vec3(dx, 0, dz).normalize();
                BlockPos retreatPos = new BlockPos(
                        (int) (player.getX() + awayDir.x * 10),
                        (int) player.getY(),
                        (int) (player.getZ() + awayDir.z * 10));
                if (!GoapNavHelper.isPathing()) {
                    GoapNavHelper.pathTo(retreatPos);
                }
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────

    @Override
    public void onDeactivated(Minecraft client) {
        if (phase == Phase.NAVIGATE || phase == Phase.RETREAT) {
            GoapNavHelper.cancelPathing();
        }
        phase = Phase.DONE;
        targetGoalId = null;
    }

    @Override
    public boolean isActive() {
        return phase != Phase.DONE;
    }

    @Override
    public int getMinimumActiveTicks() {
        return 10;
    }

    @Override
    public String getPrimaryGoalId() {
        return targetGoalId;
    }

    @Override
    public String personalityCategory() {
        return "aggression";
    }

    @Override
    public JsonObject getScoreBreakdown(WorldState state, GoalSet goals) {
        JsonObject bd = super.getScoreBreakdown(state, goals);
        bd.addProperty("phase", phase.name());
        bd.addProperty("crystals_destroyed", crystalsDestroyed);
        bd.addProperty("target_crystal_id", targetCrystalId);
        return bd;
    }
}
