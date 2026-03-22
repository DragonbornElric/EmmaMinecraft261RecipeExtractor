package com.emma.bridge.goap.actions;

import com.emma.bridge.EmmaBridgeMod;
import com.emma.bridge.control.BlockInteraction;
import com.emma.bridge.control.DirectInput;
import com.emma.bridge.goap.GoapAction;
import com.emma.bridge.goap.GoalSet;
import com.emma.bridge.goap.WorldState;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * GOAP Action: Locate the stronghold using eye of ender triangulation.
 *
 * Algorithm (from AltoClef's LocateStrongholdCoordinatesTask):
 *   1. Throw first eye of ender, track trajectory to get ray₁
 *   2. Move 30+ blocks perpendicular to ray₁
 *   3. Throw second eye, track trajectory to get ray₂
 *   4. Compute two-ray XZ intersection → stronghold coordinates
 *
 * Scores on "locate_stronghold" goal type. Returns 0 if stronghold already known.
 */
public class LocateStrongholdAction extends GoapAction {

    private enum Phase {
        THROW_FIRST, TRACK_FIRST, MOVE_PERPENDICULAR, THROW_SECOND, TRACK_SECOND,
        TRIANGULATE, NAVIGATE_TO_STRONGHOLD, DONE
    }

    private Phase phase = Phase.DONE;
    private String targetGoalId;

    // Ray data
    private Vec3 throwPos1, eyeDir1;
    private Vec3 throwPos2, eyeDir2;
    private Vec3 eyeStartPos;
    private int trackingEntityId = -1;

    // Navigation
    private int waitTicks;
    private double perpTargetX, perpTargetZ;

    // Result
    private WorldState worldStateRef;

    public void setWorldState(WorldState state) {
        this.worldStateRef = state;
    }

    @Override
    public String getName() {
        return "LocateStronghold";
    }

    @Override
    public boolean checkPreconditions(WorldState state) {
        return state.hasItemInInventory("minecraft:ender_eye", 2)
                && state.dimension.contains("overworld")
                && !state.strongholdKnown;
    }

    @Override
    public float computeScore(WorldState state, GoalSet goals) {
        if (state.strongholdKnown) return 0;

        for (GoalSet.Goal goal : goals.getGoals()) {
            if ("locate_stronghold".equals(goal.type)) {
                targetGoalId = goal.id;
                return goal.priority * 0.9f;
            }
        }
        return 0;
    }

    @Override
    public void execute(Minecraft client) {
        phase = Phase.THROW_FIRST;
        throwPos1 = null;
        throwPos2 = null;
        eyeDir1 = null;
        eyeDir2 = null;
        trackingEntityId = -1;
        waitTicks = 0;
        EmmaBridgeMod.LOGGER.info("LocateStronghold: starting triangulation");
    }

    @Override
    public void tick(Minecraft client) {
        LocalPlayer player = client.player;
        ClientLevel world = client.level;
        if (player == null || world == null) return;
        waitTicks++;

        switch (phase) {
            case THROW_FIRST -> tickThrowEye(player, true);
            case TRACK_FIRST -> tickTrackEye(player, world, true);
            case MOVE_PERPENDICULAR -> tickMovePerpendicular(player);
            case THROW_SECOND -> tickThrowEye(player, false);
            case TRACK_SECOND -> tickTrackEye(player, world, false);
            case TRIANGULATE -> tickTriangulate(player);
            case NAVIGATE_TO_STRONGHOLD -> tickNavigateToStronghold(player);
            case DONE -> {}
        }
    }

    // ── Throw an eye of ender ─────────────────────────────────────

    private void tickThrowEye(LocalPlayer player, boolean isFirst) {
        // Equip and use eye of ender
        if (!BlockInteraction.forceEquipItem(Items.ENDER_EYE)) return;

        // Look upward slightly for better trajectory visibility
        player.setXRot(-30f);

        if (waitTicks < 5) return; // Wait a few ticks for rotation to settle

        // Record throw position
        eyeStartPos = player.getEyePosition();

        // Use the item (right-click)
        Minecraft.getInstance().gameMode.useItem(player, net.minecraft.world.InteractionHand.MAIN_HAND);
        player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);

        // Transition to tracking
        trackingEntityId = -1;
        waitTicks = 0;
        phase = isFirst ? Phase.TRACK_FIRST : Phase.TRACK_SECOND;
        EmmaBridgeMod.LOGGER.info("LocateStronghold: threw eye #{}", isFirst ? 1 : 2);
    }

    // ── Track the eye entity's trajectory ─────────────────────────

    private void tickTrackEye(LocalPlayer player, ClientLevel world, boolean isFirst) {
        // Search for eye of ender entity
        EyeOfEnder eyeEntity = null;

        if (trackingEntityId != -1) {
            Entity e = world.getEntity(trackingEntityId);
            if (e instanceof EyeOfEnder eye) {
                eyeEntity = eye;
            }
        }

        if (eyeEntity == null) {
            // Scan nearby for eye entity
            AABB scanBox = player.getBoundingBox().inflate(64);
            for (Entity entity : world.getEntities(player, scanBox)) {
                if (entity instanceof EyeOfEnder eye) {
                    eyeEntity = eye;
                    trackingEntityId = eye.getId();
                    if (eyeStartPos == null) {
                        eyeStartPos = player.getEyePosition();
                    }
                    break;
                }
            }
        }

        if (eyeEntity != null && eyeEntity.isAlive()) {
            // Still flying — keep tracking
            return;
        }

        // Eye has landed or shattered
        if (eyeEntity != null || waitTicks > 100) {
            Vec3 endPos;
            if (eyeEntity != null) {
                endPos = eyeEntity.position();
            } else {
                // Timeout — estimate direction from where we threw
                EmmaBridgeMod.LOGGER.warn("LocateStronghold: eye tracking timeout, estimating");
                endPos = eyeStartPos.add(player.getViewVector(1.0f).scale(20));
            }

            // Compute direction ray (XZ only)
            Vec3 dir = endPos.subtract(eyeStartPos);
            Vec3 dirXZ = new Vec3(dir.x, 0, dir.z).normalize();

            if (isFirst) {
                throwPos1 = new Vec3(eyeStartPos.x, 0, eyeStartPos.z);
                eyeDir1 = dirXZ;
                // Move perpendicular
                perpTargetX = player.getX() + (-dirXZ.z) * 40; // rotate 90 degrees
                perpTargetZ = player.getZ() + (dirXZ.x) * 40;
                phase = Phase.MOVE_PERPENDICULAR;
                waitTicks = 0;
                EmmaBridgeMod.LOGGER.info("LocateStronghold: ray 1 direction = ({}, {})",
                        String.format("%.2f", dirXZ.x), String.format("%.2f", dirXZ.z));
            } else {
                throwPos2 = new Vec3(eyeStartPos.x, 0, eyeStartPos.z);
                eyeDir2 = dirXZ;
                phase = Phase.TRIANGULATE;
                waitTicks = 0;
                EmmaBridgeMod.LOGGER.info("LocateStronghold: ray 2 direction = ({}, {})",
                        String.format("%.2f", dirXZ.x), String.format("%.2f", dirXZ.z));
            }
        }
    }

    // ── Move perpendicular to first ray ───────────────────────────

    private void tickMovePerpendicular(LocalPlayer player) {
        double dx = perpTargetX - player.getX();
        double dz = perpTargetZ - player.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);

        if (dist < 5.0 || waitTicks > 400) {
            GoapNavHelper.cancelPathing();
            phase = Phase.THROW_SECOND;
            waitTicks = 0;
            EmmaBridgeMod.LOGGER.info("LocateStronghold: moved perpendicular, throwing eye 2");
            return;
        }

        if (!GoapNavHelper.isPathing()) {
            GoapNavHelper.pathTo(new net.minecraft.core.BlockPos(
                    (int) perpTargetX, (int) player.getY(), (int) perpTargetZ));
        }
    }

    // ── Compute intersection of two rays ──────────────────────────

    private void tickTriangulate(LocalPlayer player) {
        if (throwPos1 == null || throwPos2 == null || eyeDir1 == null || eyeDir2 == null) {
            EmmaBridgeMod.LOGGER.error("LocateStronghold: missing ray data, aborting");
            phase = Phase.DONE;
            return;
        }

        // Two-ray XZ intersection:
        // P = s1 + d1*t1 = s2 + d2*t2
        // Solve for t2: t2 = (d1.z*(s2.x-s1.x) - d1.x*(s2.z-s1.z)) / (d1.x*d2.z - d1.z*d2.x)
        double denom = eyeDir1.x * eyeDir2.z - eyeDir1.z * eyeDir2.x;

        if (Math.abs(denom) < 0.001) {
            // Parallel rays — triangulation failed
            EmmaBridgeMod.LOGGER.error("LocateStronghold: parallel rays, triangulation failed");
            phase = Phase.DONE;
            return;
        }

        double t2 = (eyeDir1.z * (throwPos2.x - throwPos1.x)
                - eyeDir1.x * (throwPos2.z - throwPos1.z)) / denom;

        double strongholdX = throwPos2.x + eyeDir2.x * t2;
        double strongholdZ = throwPos2.z + eyeDir2.z * t2;

        EmmaBridgeMod.LOGGER.info("LocateStronghold: stronghold at ({}, {})",
                (int) strongholdX, (int) strongholdZ);

        // Store in WorldState
        if (worldStateRef != null) {
            worldStateRef.strongholdKnown = true;
            worldStateRef.strongholdX = (int) strongholdX;
            worldStateRef.strongholdZ = (int) strongholdZ;
        }

        phase = Phase.DONE;
    }

    // ── Navigate toward stronghold (optional refinement) ──────────

    private void tickNavigateToStronghold(LocalPlayer player) {
        // This phase is unused for now — ActivateEndPortalAction handles navigation
        phase = Phase.DONE;
    }

    // ── Lifecycle ─────────────────────────────────────────────────

    @Override
    public void onDeactivated(Minecraft client) {
        if (phase == Phase.MOVE_PERPENDICULAR) {
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
        return 40; // Don't interrupt mid-triangulation
    }

    @Override
    public String getPrimaryGoalId() {
        return targetGoalId;
    }

    @Override
    public String personalityCategory() {
        return "exploration";
    }

    @Override
    public JsonObject getScoreBreakdown(WorldState state, GoalSet goals) {
        JsonObject bd = super.getScoreBreakdown(state, goals);
        bd.addProperty("phase", phase.name());
        bd.addProperty("has_ray1", eyeDir1 != null);
        bd.addProperty("has_ray2", eyeDir2 != null);
        return bd;
    }
}
