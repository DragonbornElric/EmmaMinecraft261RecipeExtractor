package com.emma.bridge.goap.reflex;

import com.emma.bridge.goap.GoapReflex;
import com.emma.bridge.goap.GoapStateFlags;
import com.emma.bridge.goap.WorldState;
import com.emma.bridge.util.CombatHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Pre-equip weapon reflex: equips best weapon when hostiles are nearby
 * but not yet in melee range.
 *
 * Trigger: hostiles within 8 blocks but not in ForceField range (>3.5 blocks)
 * Preconditions: not eating, not shielding, not already holding best weapon
 * Suppresses scoring: No
 */
public class PreEquipWeaponReflex extends GoapReflex {

    private static final double PRE_EQUIP_RANGE = 8.0;
    private static final double MELEE_RANGE = 3.5;

    @Override
    public String getName() {
        return "PreEquipWeapon";
    }

    @Override
    public boolean shouldFire(WorldState state, Minecraft client) {
        if (GoapStateFlags.get().isEating) return false;
        if (GoapStateFlags.get().isShielding) return false;
        if (state.threats.isEmpty()) return false;

        // Only trigger when hostiles are approaching but not yet in melee range
        // ForceFieldReflex handles melee range
        for (var threat : state.threats) {
            if (threat.distance > MELEE_RANGE && threat.distance <= PRE_EQUIP_RANGE) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void fire(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) return;

        CombatHelper.equipBestWeapon(player);
    }

    @Override
    public void release(Minecraft client) {
        // Nothing to clean up — weapon stays equipped
    }
}
