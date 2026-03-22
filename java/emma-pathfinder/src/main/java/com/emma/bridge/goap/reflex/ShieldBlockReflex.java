package com.emma.bridge.goap.reflex;

import com.emma.bridge.control.BlockInteraction;
import com.emma.bridge.control.DirectInput;
import com.emma.bridge.goap.GoapReflex;
import com.emma.bridge.goap.GoapStateFlags;
import com.emma.bridge.goap.WorldState;
import com.emma.bridge.util.InventoryScanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.zombie.Drowned;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.AABB;

/**
 * Shield block reflex: raises shield when facing imminent damage.
 *
 * Triggers:
 * - Creeper fuse > 0.5 within 6 blocks
 * - Skeleton with bow drawn within 12 blocks
 * - Any projectile approaching within 8 blocks
 *
 * Preconditions: has shield, not eating, shield not on cooldown
 * Suppresses scoring: Yes (exclusive control while blocking)
 */
public class ShieldBlockReflex extends GoapReflex {

    private int holdTicks = 0;
    private static final int MIN_HOLD_TICKS = 5;  // minimum block duration

    @Override
    public String getName() {
        return "ShieldBlock";
    }

    @Override
    public boolean suppressesScoring() {
        // Don't suppress scoring — GOAP needs to switch to AttackEntity
        // while shield is up. Shield still fires as a reflex (holds use key),
        // and AttackEntity can approach + fight between blocks.
        return false;
    }

    @Override
    public boolean shouldFire(WorldState state, Minecraft client) {
        if (!state.hasShield) return false;
        if (GoapStateFlags.get().isEating) return false;

        LocalPlayer player = client.player;
        if (player == null) return false;

        // Check shield cooldown (5 second cooldown after disable)
        if (player.getCooldowns().isOnCooldown(Items.SHIELD.getDefaultInstance())) {
            return false;
        }

        // If already active, maintain for minimum duration
        if (isActive() && holdTicks < MIN_HOLD_TICKS) return true;

        // Check creeper fuse
        AABB scanBox = player.getBoundingBox().inflate(6);
        for (Entity entity : player.level().getEntities(player, scanBox)) {
            if (entity instanceof Creeper creeper) {
                if (creeper.getSwelling(1.0f) > 0.5f) return true;
            }
        }

        // Check skeleton/stray/bogged/wither_skeleton with bow (within 12 blocks)
        // Also check pillagers with crossbow
        AABB rangedBox = player.getBoundingBox().inflate(12);
        for (Entity entity : player.level().getEntities(player, rangedBox)) {
            if (entity instanceof AbstractSkeleton skeleton) {
                if (skeleton.isUsingItem()) return true;
            }
            if (entity instanceof Pillager pillager) {
                if (pillager.isUsingItem()) return true;
            }
            // Blaze spams fireballs without a draw animation — always treat as ranged
            if (entity instanceof Blaze) return true;
            // Drowned with trident can throw it at range
            if (entity instanceof Drowned drowned) {
                if (drowned.getMainHandItem().is(Items.TRIDENT)) return true;
            }
        }

        // Check incoming projectiles (from WorldState)
        if (!state.incomingProjectiles.isEmpty()) {
            for (var proj : state.incomingProjectiles) {
                if (proj.distance < 8.0f) return true;
            }
        }

        return false;
    }

    @Override
    public void fire(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) return;

        GoapStateFlags.get().isShielding = true;

        // Face the nearest threat so shield actually blocks the damage
        Entity nearestThreat = findNearestThreat(player);
        if (nearestThreat != null) {
            BlockInteraction.lookAt(nearestThreat.getEyePosition());
        }

        // Ensure shield is in offhand
        if (!player.getOffhandItem().is(Items.SHIELD)) {
            equipShieldToOffhand(player, client);
        }

        // Hold use key (right-click) to raise shield
        DirectInput.setUseHeld(true);
        holdTicks++;
    }

    /** Find nearest hostile for facing while blocking. */
    private Entity findNearestThreat(LocalPlayer player) {
        Entity nearest = null;
        double nearestDist = Double.MAX_VALUE;
        AABB box = player.getBoundingBox().inflate(12);
        for (Entity entity : player.level().getEntities(player, box)) {
            if (entity instanceof net.minecraft.world.entity.monster.Monster) {
                double dist = player.distanceToSqr(entity);
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearest = entity;
                }
            }
        }
        return nearest;
    }

    @Override
    public void release(Minecraft client) {
        GoapStateFlags.get().isShielding = false;
        DirectInput.setUseHeld(false);
        holdTicks = 0;
    }

    /**
     * Move shield from inventory to offhand slot (slot 45 in screen handler).
     */
    private void equipShieldToOffhand(LocalPlayer player, Minecraft client) {
        int i = InventoryScanner.findSlot(player.getInventory(), stack -> stack.is(Items.SHIELD));
        if (i < 0) return;
        int syncId = player.inventoryMenu.containerId;
        // Map inventory index to screen slot (hotbar 0-8 -> screen 36-44)
        int screenSlot = (i < 9) ? i + 36 : i;
        // Pick up shield
        client.gameMode.handleContainerInput(syncId, screenSlot, 0, ContainerInput.PICKUP, player);
        // Place in offhand slot (slot 45 in player screen handler)
        client.gameMode.handleContainerInput(syncId, 45, 0, ContainerInput.PICKUP, player);
        // If there was something in offhand, put it back
        if (!player.inventoryMenu.getCarried().isEmpty()) {
            client.gameMode.handleContainerInput(syncId, screenSlot, 0, ContainerInput.PICKUP, player);
        }
    }
}
