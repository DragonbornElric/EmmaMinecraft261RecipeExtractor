package com.emma.bridge.util;

import com.emma.bridge.goap.WorldState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Combat-related utility calculations.
 * Extracted from EmmaClef's MobDefenseChain logic, rewritten using MC APIs directly.
 * No EmmaClef imports — pure Minecraft + WorldState.
 */
public final class CombatHelper {

    private CombatHelper() {}

    // ── Mob Danger Classification ─────────────────────────────────

    /**
     * Mob danger tiers for unified threat assessment.
     * EXTREME = flee unless very well geared (warden, wither boss)
     * HIGH    = flee when poorly geared (wither_skeleton, hoglin, piglin_brute, vindicator, ravager, evoker)
     * MODERATE = normal combat with some caution (enderman, blaze, drowned, creeper, witch, pillager, phantom)
     * STANDARD = basic hostiles (zombie, spider, skeleton, etc.)
     */
    public enum DangerTier {
        EXTREME(3),
        HIGH(2),
        MODERATE(1),
        STANDARD(0);

        public final int level;
        DangerTier(int level) { this.level = level; }
    }

    /**
     * Classify a mob type string into a danger tier.
     * Uses contains() for compatibility with namespaced IDs (e.g. "minecraft:wither_skeleton").
     */
    public static DangerTier getDangerTier(String mobType) {
        // Tier 3: extreme — flee unless very high gear
        if (mobType.contains("warden")) return DangerTier.EXTREME;
        // "wither" without "wither_skeleton" or "wither_rose" = Wither boss
        if (mobType.contains("wither") && !mobType.contains("wither_skeleton")
                && !mobType.contains("wither_rose")) return DangerTier.EXTREME;

        // Tier 2: high danger
        if (mobType.contains("wither_skeleton")) return DangerTier.HIGH;
        if (mobType.contains("hoglin") && !mobType.contains("zoglin")) return DangerTier.HIGH;
        if (mobType.contains("piglin_brute")) return DangerTier.HIGH;
        if (mobType.contains("vindicator")) return DangerTier.HIGH;
        if (mobType.contains("ravager")) return DangerTier.HIGH;
        if (mobType.contains("evoker")) return DangerTier.HIGH;

        // Tier 1: moderate
        if (mobType.contains("enderman")) return DangerTier.MODERATE;
        if (mobType.contains("blaze")) return DangerTier.MODERATE;
        if (mobType.contains("drowned")) return DangerTier.MODERATE;
        if (mobType.contains("creeper")) return DangerTier.MODERATE;
        if (mobType.contains("witch")) return DangerTier.MODERATE;
        if (mobType.contains("pillager")) return DangerTier.MODERATE;
        if (mobType.contains("phantom")) return DangerTier.MODERATE;

        return DangerTier.STANDARD;
    }

    /**
     * Get the highest danger tier among a list of threats.
     */
    public static DangerTier getHighestDangerTier(List<WorldState.ThreatInfo> threats) {
        DangerTier highest = DangerTier.STANDARD;
        for (WorldState.ThreatInfo threat : threats) {
            DangerTier tier = getDangerTier(threat.type);
            if (tier.level > highest.level) highest = tier;
        }
        return highest;
    }

    // ── Attack ────────────────────────────────────────────────────

    /**
     * Attack an entity if the attack cooldown is ready.
     * Equips the best weapon, performs the attack, and swings the arm.
     *
     * @return true if the attack was executed, false if cooldown not ready
     */
    public static boolean tryAttack(Minecraft client, LocalPlayer player, Entity target) {
        if (player.getAttackStrengthScale(0.0f) < 1.0f) return false;
        equipBestWeapon(player);
        client.gameMode.attack(player, target);
        player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    // ── Weapon Selection ──────────────────────────────────────────

    /**
     * Scan player inventory (slots 0-35 + offhand 40) for the item with highest attack damage.
     *
     * @return inventory slot index of the best weapon, or -1 if none found
     */
    public static int getBestWeapon(LocalPlayer player) {
        var inv = player.getInventory();
        int bestSlot = -1;
        double bestDamage = 0.0;

        // Main inventory slots 0-35
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            double damage = getWeaponDamage(stack);
            if (damage > bestDamage) {
                bestDamage = damage;
                bestSlot = i;
            }
        }

        // Offhand slot 40
        ItemStack offhand = inv.getItem(40);
        if (!offhand.isEmpty()) {
            double damage = getWeaponDamage(offhand);
            if (damage > bestDamage) {
                bestSlot = 40;
            }
        }

        return bestSlot;
    }

    /**
     * Get the attack damage value for a weapon stack.
     * Reads from the ATTRIBUTE_MODIFIERS data component, looking for the
     * attack_damage attribute modifier.
     *
     * @return attack damage value, or 1.0 (fist damage) if no modifiers found
     */
    public static double getWeaponDamage(ItemStack stack) {
        if (stack.isEmpty()) return 1.0;

        ItemAttributeModifiers modifiers = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
        if (modifiers == null) return 1.0;

        for (var entry : modifiers.modifiers()) {
            if (entry.attribute().is(Attributes.ATTACK_DAMAGE)) {
                // The modifier value is the bonus damage; base player damage is 1.0
                return 1.0 + entry.modifier().amount();
            }
        }

        return 1.0;
    }

    /**
     * Combat readiness score based on equipment.
     * Formula: armor * 3.6 / 20 + bestWeaponDamage * 0.8 + (hasShield ? 3.0 : 0.0)
     *
     * @param state current world state snapshot
     * @param bestWeaponDamage highest weapon damage from inventory (from getWeaponDamage)
     */
    public static double getEquipmentScore(WorldState state, double bestWeaponDamage) {
        double armorScore = state.armorValue * 3.6 / 20.0;
        double weaponScore = bestWeaponDamage * 0.8;
        double shieldScore = hasShield(state) ? 3.0 : 0.0;
        return armorScore + weaponScore + shieldScore;
    }

    /**
     * Danger assessment from nearby hostiles using unified DangerTier classification.
     * Base = threat count, with tier-based bonuses:
     *   EXTREME: +10 (warden, wither)
     *   HIGH:    +3  (wither_skeleton, hoglin, piglin_brute, vindicator, ravager, evoker)
     *   MODERATE: +1 (enderman, blaze, drowned, creeper, witch)
     *   STANDARD: +0 (zombie, spider, skeleton, etc.)
     */
    public static double getThreatScore(List<WorldState.ThreatInfo> threats) {
        if (threats == null || threats.isEmpty()) return 0.0;

        double score = threats.size();
        for (WorldState.ThreatInfo threat : threats) {
            DangerTier tier = getDangerTier(threat.type);
            switch (tier) {
                case EXTREME  -> score += 10.0;
                case HIGH     -> score += 3.0;
                case MODERATE -> score += 1.0;
                default -> {}
            }
        }
        return score;
    }

    /**
     * Equip the best weapon to the player's main hand.
     * Finds the highest damage weapon in inventory and swaps it to the selected hotbar slot.
     *
     * @param player the player entity
     * @return true if a weapon was equipped (or already held), false if no weapons found
     */
    public static boolean equipBestWeapon(LocalPlayer player) {
        int bestSlot = getBestWeapon(player);
        if (bestSlot >= 0) {
            // If already in hotbar, just select it
            if (bestSlot < 9) {
                player.getInventory().setSelectedSlot(bestSlot);
                return true;
            }

            // If in main inventory (9-35), swap with current hotbar slot
            if (bestSlot < 36) {
                int currentSlot = player.getInventory().getSelectedSlot();
                net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
                int syncId = player.inventoryMenu.containerId;
                client.gameMode.handleContainerInput(syncId, bestSlot, 0,
                        net.minecraft.world.inventory.ContainerInput.PICKUP, player);
                client.gameMode.handleContainerInput(syncId, 36 + currentSlot, 0,
                        net.minecraft.world.inventory.ContainerInput.PICKUP, player);
                if (!player.inventoryMenu.getCarried().isEmpty()) {
                    client.gameMode.handleContainerInput(syncId, bestSlot, 0,
                            net.minecraft.world.inventory.ContainerInput.PICKUP, player);
                }
                return true;
            }
        }

        // Fallback: extract best weapon from Endless Inventory
        if (EndinvBridge.isAvailable()) {
            String bestWeaponId = null;
            double bestDamage = 0;
            for (var entry : EndinvBridge.getAllItems().entrySet()) {
                String id = entry.getKey();
                if (id.contains("sword") || id.contains("axe") || id.contains("mace") || id.contains("trident")) {
                    double dmg = ItemClassifier.getMaterialTier(id);
                    if (dmg > bestDamage) {
                        bestDamage = dmg;
                        bestWeaponId = id;
                    }
                }
            }
            if (bestWeaponId != null) {
                return EndinvBridge.extractToSlot(bestWeaponId,
                        player.getInventory().getSelectedSlot());
            }
        }

        return false;
    }

    /**
     * Check if the player has a shield in their inventory (any scope).
     */
    private static boolean hasShield(WorldState state) {
        return state.playerInventory.containsKey("minecraft:shield");
    }
}
