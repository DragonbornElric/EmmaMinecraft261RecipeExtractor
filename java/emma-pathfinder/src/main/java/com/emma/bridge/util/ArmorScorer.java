package com.emma.bridge.util;

import com.emma.bridge.goap.WorldState;
import com.emma.bridge.goap.WorldState.ArmorState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.Map;

/**
 * Single source of truth for armor scoring, enchantment weighting, and
 * Nether gold helmet logic. Used by both {@code EquipBestArmorAction} and
 * {@code ArmorEquipReflex}.
 *
 * Score formula: (material_tier x 10) + enchantment_score + (durability_pct x 2)
 * Enchantment score: sum(enchant_level x base_value x dimension_weight)
 */
public final class ArmorScorer {

    private ArmorScorer() {}

    private static final float LOW_DURABILITY_THRESHOLD = 0.05f;
    private static final float LOW_DURABILITY_PENALTY = -15.0f;

    // -- Enchantment base values (per level) ----------------------------------

    static final Map<String, Float> ENCHANT_BASE = Map.ofEntries(
            Map.entry("protection",            4.0f),
            Map.entry("fire_protection",       4.0f),
            Map.entry("blast_protection",      4.0f),
            Map.entry("projectile_protection", 4.0f),
            Map.entry("feather_falling",       5.0f),
            Map.entry("thorns",                1.0f),
            Map.entry("respiration",           3.0f),
            Map.entry("aqua_affinity",         2.0f),
            Map.entry("depth_strider",         2.0f),
            Map.entry("frost_walker",          1.0f),
            Map.entry("soul_speed",            2.0f),
            Map.entry("swift_sneak",           1.0f),
            Map.entry("unbreaking",            2.0f),
            Map.entry("mending",               3.0f)
    );

    // -- Dimension weight multipliers [overworld, nether, end] ----------------

    static final Map<String, float[]> DIMENSION_WEIGHTS = Map.ofEntries(
            //                                    OW    Nether  End
            Map.entry("protection",            new float[]{1.0f, 0.7f, 0.8f}),
            Map.entry("fire_protection",       new float[]{0.1f, 1.5f, 0.0f}),
            Map.entry("blast_protection",      new float[]{0.6f, 1.2f, 0.1f}),
            Map.entry("projectile_protection", new float[]{0.7f, 0.3f, 1.3f}),
            Map.entry("feather_falling",       new float[]{0.5f, 0.6f, 1.5f}),
            Map.entry("thorns",                new float[]{0.3f, 0.3f, 0.5f}),
            Map.entry("respiration",           new float[]{0.8f, 0.0f, 0.1f}),
            Map.entry("aqua_affinity",         new float[]{0.6f, 0.0f, 0.0f}),
            Map.entry("depth_strider",         new float[]{0.7f, 0.0f, 0.1f}),
            Map.entry("frost_walker",          new float[]{0.3f, 0.0f, 0.0f}),
            Map.entry("soul_speed",            new float[]{0.0f, 1.0f, 0.0f}),
            Map.entry("swift_sneak",           new float[]{0.5f, 0.5f, 0.5f}),
            Map.entry("unbreaking",            new float[]{1.0f, 1.0f, 1.0f}),
            Map.entry("mending",               new float[]{1.0f, 1.0f, 1.0f})
    );

    // -- Scoring --------------------------------------------------------------

    /**
     * Score an armor piece for a given dimension.
     * Higher = better. Returns -100 for Curse of Binding.
     */
    public static float score(ArmorState armor, int dimIndex) {
        if (armor.hasCurseOfBinding) return -100.0f;

        float materialScore = ItemClassifier.getMaterialTier(armor.item) * 10.0f;
        float enchantScore = computeEnchantmentScore(armor.enchantments, dimIndex);
        float durabilityScore = armor.durabilityPercent * 2.0f;

        float total = materialScore + enchantScore + durabilityScore;

        if (armor.durabilityPercent < LOW_DURABILITY_THRESHOLD) {
            total += LOW_DURABILITY_PENALTY;
        }

        return total;
    }

    /**
     * Enchantment score = sum(enchant_level x base_value x dimension_weight)
     */
    public static float computeEnchantmentScore(Map<String, Integer> enchantments, int dimIndex) {
        float total = 0;
        for (var entry : enchantments.entrySet()) {
            String enchName = entry.getKey();
            int level = entry.getValue();

            float baseValue = ENCHANT_BASE.getOrDefault(enchName, 1.0f);
            float dimWeight = getDimensionWeight(enchName, dimIndex);

            total += level * baseValue * dimWeight;
        }
        return total;
    }

    public static float getDimensionWeight(String enchantment, int dimIndex) {
        float[] weights = DIMENSION_WEIGHTS.get(enchantment);
        if (weights == null) return 1.0f;
        if (dimIndex < 0 || dimIndex >= weights.length) return weights[0];
        return weights[dimIndex];
    }

    // -- Nether gold helmet ---------------------------------------------------

    /** Check whether a gold helmet is currently equipped (WorldState-based). */
    public static boolean hasGoldHelmetEquipped(WorldState state) {
        ArmorState head = state.equippedArmor.get(EquipmentSlot.HEAD);
        return head != null && ItemClassifier.isGoldItem(head.item);
    }

    /** Check whether the live player has a gold helmet equipped. */
    public static boolean hasGoldHelmetEquipped(LocalPlayer player) {
        ItemStack equipped = player.getItemBySlot(EquipmentSlot.HEAD);
        return ItemClassifier.isGoldItem(ItemClassifier.itemId(equipped));
    }

    /**
     * Find a gold helmet in inventory (slots 0-35) and shift-click it on.
     * @return true if a gold helmet was found and equipped
     */
    public static boolean equipGoldHelmet(Minecraft client, LocalPlayer player) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            String id = ItemClassifier.itemId(stack);
            if (ItemClassifier.getArmorSlot(id) == EquipmentSlot.HEAD && ItemClassifier.isGoldItem(id)) {
                ItemClassifier.shiftClick(client, player, i);
                return true;
            }
        }
        return false;
    }
}
