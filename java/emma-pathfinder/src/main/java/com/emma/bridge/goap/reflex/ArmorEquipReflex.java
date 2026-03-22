package com.emma.bridge.goap.reflex;

import com.emma.bridge.EmmaBridgeMod;
import com.emma.bridge.goap.GoapReflex;
import com.emma.bridge.goap.GoapStateFlags;
import com.emma.bridge.goap.WorldState;
import com.emma.bridge.goap.WorldState.ArmorCandidate;
import com.emma.bridge.goap.WorldState.ArmorState;
import com.emma.bridge.util.ArmorScorer;
import com.emma.bridge.util.ItemClassifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * Auto-equip best armor reflex.
 *
 * Runs every tick WITHOUT suppressing scoring — equips armor alongside
 * whatever GOAP action is running. If Emma picks up diamond armor while
 * farming, she puts it on immediately without waiting for the auction.
 *
 * Cooldown: only attempts equip every 20 ticks (1 second) to avoid
 * spamming slot clicks.
 *
 * Hard override: Nether forces gold helmet (Piglin aggro prevention).
 */
public class ArmorEquipReflex extends GoapReflex {

    private static final int EQUIP_COOLDOWN = 20; // ticks between equip attempts
    private int cooldownTimer = 0;

    @Override
    public String getName() {
        return "ArmorEquip";
    }

    @Override
    public boolean suppressesScoring() {
        return false; // runs alongside GOAP actions
    }

    @Override
    public boolean shouldFire(WorldState state, Minecraft client) {
        if (GoapStateFlags.get().isEating) return false;
        if (GoapStateFlags.get().isShielding) return false;
        if (client.player == null) return false;

        cooldownTimer++;
        if (cooldownTimer < EQUIP_COOLDOWN) return false;
        cooldownTimer = 0;

        // Check if any armor upgrade is available
        return !state.availableArmor.isEmpty() && hasUpgrade(state, client);
    }

    @Override
    public void fire(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) return;

        int dimIndex = ItemClassifier.dimensionIndex(
                player.level().dimension().identifier().toString());
        boolean inNether = dimIndex == 1;

        EquipmentSlot[] slots = {
                EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET
        };

        for (EquipmentSlot slot : slots) {
            // Nether gold helmet override
            if (inNether && slot == EquipmentSlot.HEAD) {
                if (!ArmorScorer.hasGoldHelmetEquipped(player)) {
                    if (ArmorScorer.equipGoldHelmet(client, player)) {
                        EmmaBridgeMod.LOGGER.info("[ArmorEquip] Equipped gold helmet (Nether)");
                    }
                }
                continue;
            }

            // Standard: find best candidate for this slot
            float currentScore = 0;
            ItemStack equipped = player.getItemBySlot(slot);
            if (!equipped.isEmpty()) {
                ArmorState current = ArmorState.fromStack(equipped);
                if (current.hasCurseOfBinding) continue; // can't swap
                currentScore = ArmorScorer.score(current, dimIndex);
            }

            // Don't unequip gold helmet in Nether
            if (inNether && slot == EquipmentSlot.HEAD && ItemClassifier.isGoldItem(ItemClassifier.itemId(equipped))) {
                continue;
            }

            int bestSlot = -1;
            float bestScore = currentScore;
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
                    bestSlot = i;
                }
            }

            if (bestSlot >= 0) {
                ItemClassifier.shiftClick(client, player, bestSlot);
                EmmaBridgeMod.LOGGER.info("[ArmorEquip] Upgraded {} slot (score {} -> {})",
                        slot.getName(), currentScore, bestScore);
            }
        }
    }

    @Override
    public void release(Minecraft client) {
        // No cleanup needed
    }

    // -- Helpers --------------------------------------------------------------

    private boolean hasUpgrade(WorldState state, Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) return false;

        int dimIndex = ItemClassifier.dimensionIndex(
                player.level().dimension().identifier().toString());
        boolean inNether = dimIndex == 1;

        for (ArmorCandidate candidate : state.availableArmor) {
            if (candidate.hasCurseOfBinding) continue;
            EquipmentSlot slot = ItemClassifier.getArmorSlot(candidate.item);
            if (slot == null) continue;

            // Nether gold helmet check
            if (inNether && slot == EquipmentSlot.HEAD && !ItemClassifier.isGoldItem(candidate.item)) {
                if (state.equippedArmor.containsKey(EquipmentSlot.HEAD)
                        && ItemClassifier.isGoldItem(state.equippedArmor.get(EquipmentSlot.HEAD).item)) {
                    continue;
                }
            }

            float candidateScore = ArmorScorer.score(candidate, dimIndex);
            float currentScore = 0;
            ArmorState current = state.equippedArmor.get(slot);
            if (current != null && !"empty".equals(current.item)) {
                currentScore = ArmorScorer.score(current, dimIndex);
            }

            if (candidateScore > currentScore) return true;

            // Nether: need gold helmet but don't have one
            if (inNether && slot == EquipmentSlot.HEAD && ItemClassifier.isGoldItem(candidate.item)) {
                if (!ArmorScorer.hasGoldHelmetEquipped(player)) return true;
            }
        }
        return false;
    }
}
