package com.emma.bridge.goap.reflex;

import com.emma.bridge.control.BlockInteraction;
import com.emma.bridge.control.DirectInput;
import com.emma.bridge.goap.GoapReflex;
import com.emma.bridge.goap.GoapStateFlags;
import com.emma.bridge.goap.WorldState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.BlockPos;

/**
 * MLG bucket reflex: places water bucket when falling to prevent fall damage.
 *
 * Trigger: velocityY < -0.7, not swimming, not climbing, not on ground
 * Action: look straight down, place water bucket, after landing pick up water
 * Chorus fruit failsafe: if no bucket + falling fast, eat chorus fruit
 * Suppresses scoring: Yes (exclusive control during MLG sequence)
 */
public class MLGBucketReflex extends GoapReflex {

    private enum Phase { FALLING, PLACED, PICKUP, CHORUS_WAIT }

    private Phase phase = Phase.FALLING;
    private BlockPos waterPos = null;
    private int pickupDelay = 0;
    private int chorusTicks = 0;

    @Override
    public String getName() {
        return "MLGBucket";
    }

    @Override
    public boolean suppressesScoring() {
        return true;
    }

    @Override
    public boolean shouldFire(WorldState state, Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) return false;

        // If we're in the middle of an MLG sequence, keep going
        if (isActive() && (phase == Phase.PLACED || phase == Phase.PICKUP || phase == Phase.CHORUS_WAIT)) {
            return true;
        }

        // Detect dangerous fall: fast downward velocity, not grounded/swimming/climbing
        if (state.velocityY < -0.7
                && !player.onGround()
                && !player.isInWater()
                && !player.onClimbable()) {
            return true;
        }

        return false;
    }

    @Override
    public void fire(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) return;

        GoapStateFlags.get().isFalling = true;
        GoapStateFlags.get().isMLGActive = true;

        switch (phase) {
            case FALLING -> handleFalling(player, client);
            case PLACED -> handlePlaced(player);
            case PICKUP -> handlePickup(player, client);
            case CHORUS_WAIT -> handleChorusWait(player);
        }
    }

    private void handleFalling(LocalPlayer player, Minecraft client) {
        // Look straight down
        player.setXRot(90.0f);

        boolean hasBucket = BlockInteraction.forceEquipItem(Items.WATER_BUCKET);
        if (hasBucket) {
            // Place water when close to ground (velocity check + distance)
            // Use interactItem — BucketItem.use() does its own raycast
            InteractionResult result = client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
            if (result.consumesAction()) {
                player.swing(InteractionHand.MAIN_HAND);
                waterPos = player.blockPosition().below();
                phase = Phase.PLACED;
            }
        } else {
            // Chorus fruit failsafe — eat chorus fruit to teleport
            boolean hasChorus = BlockInteraction.forceEquipItem(Items.CHORUS_FRUIT);
            if (hasChorus) {
                GoapStateFlags.get().isChorusFruiting = true;
                DirectInput.setUseHeld(true);
                phase = Phase.CHORUS_WAIT;
                chorusTicks = 0;
            }
            // No bucket and no chorus — nothing we can do
        }
    }

    private void handlePlaced(LocalPlayer player) {
        // Wait until we land (on ground or in water)
        if (player.onGround() || player.isInWater()) {
            phase = Phase.PICKUP;
            pickupDelay = 5; // wait a few ticks before picking up
        }
    }

    private void handlePickup(LocalPlayer player, Minecraft client) {
        pickupDelay--;
        if (pickupDelay > 0) return;

        // Pick up the water with an empty bucket
        if (waterPos != null && BlockInteraction.forceEquipItem(Items.BUCKET)) {
            // Look at water position and collect
            player.setXRot(90.0f); // look down at water
            InteractionResult result = client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
            if (result.consumesAction()) {
                player.swing(InteractionHand.MAIN_HAND);
            }
        }

        // Done regardless of whether pickup succeeded
        phase = Phase.FALLING;
        waterPos = null;
    }

    private void handleChorusWait(LocalPlayer player) {
        chorusTicks++;
        // Chorus fruit takes 32 ticks (1.6 seconds) to eat
        if (chorusTicks > 35 || player.onGround()) {
            DirectInput.setUseHeld(false);
            GoapStateFlags.get().isChorusFruiting = false;
            phase = Phase.FALLING;
            chorusTicks = 0;
        }
    }

    @Override
    public void release(Minecraft client) {
        GoapStateFlags.get().isFalling = false;
        GoapStateFlags.get().isMLGActive = false;
        GoapStateFlags.get().isChorusFruiting = false;
        DirectInput.setUseHeld(false);
        phase = Phase.FALLING;
        waterPos = null;
        pickupDelay = 0;
        chorusTicks = 0;
    }
}
