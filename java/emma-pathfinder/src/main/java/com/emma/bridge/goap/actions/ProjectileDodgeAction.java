package com.emma.bridge.goap.actions;

import com.emma.bridge.control.DirectInput;
import com.emma.bridge.goap.GoapAction;
import com.emma.bridge.goap.GoapStateFlags;
import com.emma.bridge.goap.GoalSet;
import com.emma.bridge.goap.WorldState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Dodge incoming projectiles when no shield is available.
 *
 * Uses projectile velocity vectors to compute perpendicular dodge direction.
 * Falls back to alternating strafe if no velocity data available.
 * Category: safety
 */
public class ProjectileDodgeAction extends GoapAction {

    private boolean active = false;
    private boolean strafeLeft = true;  // fallback alternate direction

    @Override
    public String getName() {
        return "ProjectileDodge";
    }

    @Override
    public String personalityCategory() {
        return "safety";
    }

    @Override
    public boolean checkPreconditions(WorldState state) {
        // Dodge if no shield AND projectile incoming AND not already shielding
        return !state.hasShield
                && !state.incomingProjectiles.isEmpty()
                && !GoapStateFlags.get().isShielding;
    }

    @Override
    public float computeScore(WorldState state, GoalSet goals) {
        if (!checkPreconditions(state)) return 0f;

        float survive = goals.getGoalPriority("survive");
        if (survive <= 0) survive = 8.0f;

        // Urgency based on closest projectile distance
        float closestDist = state.incomingProjectiles.getFirst().distance;
        float urgency = 1.0f;
        if (closestDist < 3.0f) urgency = 1.5f;
        else if (closestDist < 6.0f) urgency = 1.2f;

        // Health scaling: more urgent when low health
        float healthMult = 1.0f + (1.0f - state.health / state.maxHealth) * 0.5f;

        // Multiple projectiles: more urgent
        float projMult = Math.min(1.3f, 1.0f + (state.incomingProjectiles.size() - 1) * 0.1f);

        return survive * urgency * healthMult * projMult * 0.8f;
    }

    @Override
    public void execute(Minecraft client) {
        active = true;
        strafe(client);
    }

    @Override
    public void tick(Minecraft client) {
        if (active) {
            strafe(client);
        }
    }

    @Override
    public void onDeactivated(Minecraft client) {
        active = false;
        DirectInput.setLeft(false);
        DirectInput.setRight(false);
        DirectInput.setForward(false);
        DirectInput.setSprinting(false);
    }

    @Override
    public boolean isActive() {
        return active;
    }

    private void strafe(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) return;

        // Find nearest projectile and use its velocity for directional dodge
        Projectile nearest = findNearestProjectile(client);
        boolean usedVelocity = false;

        if (nearest != null) {
            Vec3 vel = nearest.getDeltaMovement();
            if (vel.x != 0 || vel.z != 0) {
                // Perpendicular to approach vector (rotate 90°): (-velZ, velX)
                double perpX = -vel.z;
                double perpZ = vel.x;

                // Player's right direction from yaw
                float yaw = player.getYRot();
                double rightX = -Math.cos(Math.toRadians(yaw));
                double rightZ = -Math.sin(Math.toRadians(yaw));

                // Dot product: positive = perp aligns with player's right
                double dot = perpX * rightX + perpZ * rightZ;

                if (dot >= 0) {
                    DirectInput.setRight(true);
                    DirectInput.setLeft(false);
                } else {
                    DirectInput.setLeft(true);
                    DirectInput.setRight(false);
                }
                usedVelocity = true;
            }
        }

        if (!usedVelocity) {
            // Fallback: alternate strafe direction
            if (strafeLeft) {
                DirectInput.setLeft(true);
                DirectInput.setRight(false);
            } else {
                DirectInput.setLeft(false);
                DirectInput.setRight(true);
            }
            strafeLeft = !strafeLeft;
        }

        // Sprint + forward to escape faster
        DirectInput.setSprinting(true);
        DirectInput.setForward(true);
    }

    private Projectile findNearestProjectile(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || player.level() == null) return null;

        AABB scanBox = player.getBoundingBox().inflate(16);
        Projectile nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (Entity entity : player.level().getEntities(player, scanBox)) {
            if (entity instanceof Projectile proj) {
                double dist = player.distanceToSqr(proj);
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearest = proj;
                }
            }
        }
        return nearest;
    }
}
