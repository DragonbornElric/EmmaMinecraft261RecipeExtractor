package com.emma.bridge.goap.actions;

import com.emma.bridge.goap.GoapAction;
import com.emma.bridge.goap.GoalSet;
import com.emma.bridge.goap.WorldState;
import com.emma.bridge.goap.WorldState.ArmorCandidate;
import com.emma.bridge.goap.WorldState.ArmorState;
import com.emma.bridge.util.ArmorScorer;
import com.emma.bridge.util.ItemClassifier;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * GOAP Action: Equip the best available armor from inventory.
 *
 * Scores each armor piece using:
 *   armor_score = (material_tier x 10) + enchantment_score + (durability_pct x 2)
 *
 * Enchantment scores are weighted by dimension:
 *   enchantment_score = sum(enchant_level x base_value x dimension_weight)
 *
 * Hard overrides bypass scoring entirely:
 *   - Nether + gold helmet available -> force gold helmet (Piglin aggro)
 *   - Curse of Binding -> never swap TO this piece
 *   - Durability < 5% -> soft deprioritize
 */
public class EquipBestArmorAction extends GoapAction {

    private boolean swapping = false;
    private float improvementScore = 0;
    private String bestUpgradeSlot = null;

    @Override
    public String getName() {
        return "EquipBestArmor";
    }

    @Override
    public boolean checkPreconditions(WorldState state) {
        return !state.availableArmor.isEmpty();
    }

    @Override
    public float computeScore(WorldState state, GoalSet goals) {
        if (state.availableArmor.isEmpty()) return 0;

        float goalPriority = goals.getGoal("survive")
                .map(g -> g.priority)
                .orElse(8.0f);

        int dimIndex = ItemClassifier.dimensionIndex(state.dimension);
        boolean inNether = dimIndex == 1;

        // Hard override: Nether gold helmet check
        if (inNether && !ArmorScorer.hasGoldHelmetEquipped(state)) {
            for (ArmorCandidate candidate : state.availableArmor) {
                if (ItemClassifier.getArmorSlot(candidate.item) == EquipmentSlot.HEAD
                        && ItemClassifier.isGoldItem(candidate.item)) {
                    // Survival-level urgency -- Piglin aggro prevention
                    improvementScore = 100;
                    bestUpgradeSlot = "HEAD";
                    return goalPriority * 1.0f;
                }
            }
        }

        // Standard scoring: find best improvement across all slots
        float bestImprovement = 0;
        String bestSlot = null;

        for (ArmorCandidate candidate : state.availableArmor) {
            // Hard block: never swap TO Curse of Binding
            if (candidate.hasCurseOfBinding) continue;

            EquipmentSlot slot = ItemClassifier.getArmorSlot(candidate.item);
            if (slot == null) continue;

            float candidateScore = ArmorScorer.score(candidate, dimIndex);
            float currentScore = 0;

            ArmorState current = state.equippedArmor.get(slot);
            if (current != null && !"empty".equals(current.item)) {
                currentScore = ArmorScorer.score(current, dimIndex);
            }

            // Nether: don't unequip gold helmet for a non-gold helmet
            if (inNether && slot == EquipmentSlot.HEAD
                    && ArmorScorer.hasGoldHelmetEquipped(state) && !ItemClassifier.isGoldItem(candidate.item)) {
                continue;
            }

            float improvement = candidateScore - currentScore;
            if (improvement > bestImprovement) {
                bestImprovement = improvement;
                bestSlot = slot.getName();
            }
        }

        if (bestImprovement <= 0) return 0;

        improvementScore = bestImprovement;
        bestUpgradeSlot = bestSlot;

        // Normalize improvement (typical scored range 0-80)
        float normalizedImprovement = Math.min(bestImprovement / 40.0f, 1.0f);

        return goalPriority * normalizedImprovement * 0.5f;
    }

    @Override
    public void execute(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) return;

        int dimIndex = ItemClassifier.dimensionIndex(
                player.level().dimension().identifier().toString());
        boolean inNether = dimIndex == 1;

        try {
            // For each armor slot, find the best candidate and shift-click it
            EquipmentSlot[] slots = {
                    EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                    EquipmentSlot.LEGS, EquipmentSlot.FEET
            };

            for (EquipmentSlot slot : slots) {
                ArmorCandidate best = null;
                float bestScore = -1;

                // Score what's currently equipped
                ItemStack equipped = player.getItemBySlot(slot);
                float currentScore = 0;
                if (!equipped.isEmpty()) {
                    ArmorState current = ArmorState.fromStack(equipped);
                    currentScore = ArmorScorer.score(current, dimIndex);
                }

                // Nether gold helmet hard override
                if (inNether && slot == EquipmentSlot.HEAD) {
                    if (!ArmorScorer.hasGoldHelmetEquipped(player)) {
                        ArmorScorer.equipGoldHelmet(client, player);
                    }
                    continue;
                }

                // Find best candidate for this slot
                for (int i = 0; i < 36; i++) {
                    ItemStack stack = player.getInventory().getItem(i);
                    if (stack.isEmpty()) continue;
                    String id = ItemClassifier.itemId(stack);
                    if (ItemClassifier.getArmorSlot(id) != slot) continue;

                    ArmorCandidate candidate = ArmorCandidate.fromStack(stack, i);
                    if (candidate.hasCurseOfBinding) continue;

                    float score = ArmorScorer.score(candidate, dimIndex);
                    if (score > bestScore) {
                        bestScore = score;
                        best = candidate;
                    }
                }

                if (best != null && bestScore > currentScore) {
                    ItemClassifier.shiftClick(client, player, best.inventorySlot);
                }
            }
            swapping = true;
        } catch (Exception e) {
            swapping = false;
        }
    }

    @Override
    public void tick(Minecraft client) {
        if (swapping) {
            swapping = false;
        }
    }

    @Override
    public void onDeactivated(Minecraft client) {
        swapping = false;
        improvementScore = 0;
        bestUpgradeSlot = null;
    }

    @Override
    public boolean isActive() {
        return swapping;
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
        bd.addProperty("swapping", swapping);
        bd.addProperty("improvement_score", improvementScore);
        bd.addProperty("best_upgrade_slot", bestUpgradeSlot);
        bd.addProperty("available_armor_count", state.availableArmor.size());
        bd.addProperty("dimension", state.dimension);

        int dimIndex = ItemClassifier.dimensionIndex(state.dimension);

        // Show current equipped armor with full scores
        JsonObject equipped = new JsonObject();
        for (var entry : state.equippedArmor.entrySet()) {
            ArmorState armor = entry.getValue();
            if (!"empty".equals(armor.item)) {
                JsonObject detail = new JsonObject();
                detail.addProperty("item", armor.item);
                detail.addProperty("score", ArmorScorer.score(armor, dimIndex));
                detail.addProperty("material_tier", ItemClassifier.getMaterialTier(armor.item));
                detail.addProperty("durability_pct", armor.durabilityPercent);
                detail.addProperty("enchant_score", ArmorScorer.computeEnchantmentScore(armor.enchantments, dimIndex));
                detail.addProperty("curse_of_binding", armor.hasCurseOfBinding);
                equipped.add(entry.getKey().getName(), detail);
            }
        }
        bd.add("equipped", equipped);

        // Show best candidate per slot
        JsonObject candidates = new JsonObject();
        for (ArmorCandidate c : state.availableArmor) {
            EquipmentSlot slot = ItemClassifier.getArmorSlot(c.item);
            if (slot == null) continue;
            float score = ArmorScorer.score(c, dimIndex);
            String slotName = slot.getName();
            if (!candidates.has(slotName)
                    || candidates.getAsJsonObject(slotName).get("score").getAsFloat() < score) {
                JsonObject cj = new JsonObject();
                cj.addProperty("item", c.item);
                cj.addProperty("score", score);
                cj.addProperty("slot_index", c.inventorySlot);
                candidates.add(slotName, cj);
            }
        }
        bd.add("best_candidates", candidates);

        return bd;
    }
}
