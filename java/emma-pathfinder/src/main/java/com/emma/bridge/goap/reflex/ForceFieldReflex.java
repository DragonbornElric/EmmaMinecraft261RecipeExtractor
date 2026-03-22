package com.emma.bridge.goap.reflex;

import com.emma.bridge.goap.GoapReflex;
import com.emma.bridge.goap.GoapStateFlags;
import com.emma.bridge.goap.WorldState;
import com.emma.bridge.util.CombatHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.AABB;
import com.emma.bridge.control.BlockInteraction;

/**
 * Force field reflex: swings at the nearest hostile within melee range every tick.
 *
 * Trigger: hostile within 3.5 blocks
 * Preconditions: not eating, not falling (MLG takes priority)
 * Suppresses scoring: No (GOAP can still pick actions while swinging)
 */
public class ForceFieldReflex extends GoapReflex {

    private static final double MELEE_RANGE = 3.5;

    @Override
    public String getName() {
        return "ForceField";
    }

    @Override
    public boolean suppressesScoring() {
        return false;
    }

    @Override
    public boolean shouldFire(WorldState state, Minecraft client) {
        if (GoapStateFlags.get().isEating) return false;
        if (GoapStateFlags.get().isFalling) return false;
        if (GoapStateFlags.get().isShielding) return false;

        // Check if any hostile is within melee range
        for (var threat : state.threats) {
            if (threat.distance <= MELEE_RANGE) return true;
        }
        return false;
    }

    @Override
    public void fire(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) return;

        GoapStateFlags.get().isForceFieldActive = true;

        // Find nearest hostile within range
        Entity nearest = null;
        double nearestDist = MELEE_RANGE + 1;
        AABB box = player.getBoundingBox().inflate(MELEE_RANGE);
        for (Entity entity : player.level().getEntities(player, box)) {
            if (entity instanceof Monster hostile) {
                // Skip neutral endermen — don't provoke them
                if (hostile instanceof EnderMan enderMan && !enderMan.isCreepy()) continue;
                double dist = player.distanceTo(hostile);
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearest = hostile;
                }
            }
        }

        if (nearest != null) {
            BlockInteraction.lookAt(nearest.getEyePosition());
            CombatHelper.tryAttack(client, player, nearest);
        }
    }

    @Override
    public void release(Minecraft client) {
        GoapStateFlags.get().isForceFieldActive = false;
    }
}
