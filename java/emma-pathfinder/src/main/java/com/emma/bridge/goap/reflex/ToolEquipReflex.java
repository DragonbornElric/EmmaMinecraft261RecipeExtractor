package com.emma.bridge.goap.reflex;

import com.emma.bridge.goap.GoapReflex;
import com.emma.bridge.goap.GoapStateFlags;
import com.emma.bridge.goap.WorldState;
import emmatone.utils.ToolSet;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Tool equip reflex: auto-swaps to the best tool when breaking a block,
 * and handles stuck shift key + stuck cursor stack cleanup.
 *
 * Trigger: currently breaking a block + better tool in inventory
 * Suppresses scoring: No
 */
public class ToolEquipReflex extends GoapReflex {

    private int shiftHeldTicks = 0;
    private int cursorStackTicks = 0;
    private static final int SHIFT_STUCK_THRESHOLD = 200;  // ~10 seconds
    private static final int CURSOR_STUCK_THRESHOLD = 20;   // ~1 second

    @Override
    public String getName() {
        return "ToolEquip";
    }

    @Override
    public boolean shouldFire(WorldState state, Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) return false;

        // Don't mess with hotbar or cursor during eating
        if (GoapStateFlags.get().isEating) return false;

        // Check for stuck shift key
        if (player.input.keyPresses.shift()) {
            shiftHeldTicks++;
            if (shiftHeldTicks > SHIFT_STUCK_THRESHOLD) return true;
        } else {
            shiftHeldTicks = 0;
        }

        // Check for stuck cursor stack
        if (!player.inventoryMenu.getCarried().isEmpty()) {
            cursorStackTicks++;
            if (cursorStackTicks > CURSOR_STUCK_THRESHOLD) return true;
        } else {
            cursorStackTicks = 0;
        }

        // Check if we're breaking a block and have a better tool
        if (client.gameMode != null && client.gameMode.isDestroying()) {
            return hasBetterTool(player, client);
        }

        return false;
    }

    @Override
    public void fire(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) return;

        // Fix stuck shift key
        if (shiftHeldTicks > SHIFT_STUCK_THRESHOLD) {
            player.setShiftKeyDown(false);
            shiftHeldTicks = 0;
            return;
        }

        // Fix stuck cursor stack — drop it
        if (cursorStackTicks > CURSOR_STUCK_THRESHOLD) {
            if (!player.inventoryMenu.getCarried().isEmpty()) {
                // Click outside inventory window to drop cursor stack
                client.gameMode.handleContainerInput(
                        player.inventoryMenu.containerId,
                        -999, 0,
                        net.minecraft.world.inventory.ContainerInput.PICKUP,
                        player);
            }
            cursorStackTicks = 0;
            return;
        }

        // Auto-equip best tool for current block
        equipBestToolForBreaking(player, client);
    }

    @Override
    public void release(Minecraft client) {
        shiftHeldTicks = 0;
        cursorStackTicks = 0;
    }

    /**
     * Check if there's a better tool in inventory for the block being broken.
     * Delegates to Emmatone's ToolSet which accounts for enchantments, potion
     * effects, material cost, item saver, and sword exclusion.
     */
    private boolean hasBetterTool(LocalPlayer player, Minecraft client) {
        BlockState targetBlock = getBreakingBlockState(player, client);
        if (targetBlock == null) return false;

        ToolSet toolSet = new ToolSet(player);
        int bestSlot = toolSet.getBestSlot(targetBlock.getBlock(), false);
        return bestSlot != player.getInventory().getSelectedSlot();
    }

    /**
     * Equip the best tool from hotbar for the current breaking block.
     * Delegates to Emmatone's ToolSet for full-featured tool selection.
     */
    private void equipBestToolForBreaking(LocalPlayer player, Minecraft client) {
        BlockState targetBlock = getBreakingBlockState(player, client);
        if (targetBlock == null) return;

        ToolSet toolSet = new ToolSet(player);
        int bestSlot = toolSet.getBestSlot(targetBlock.getBlock(), false);
        if (bestSlot != player.getInventory().getSelectedSlot()) {
            player.getInventory().setSelectedSlot(bestSlot);
        }
    }

    /**
     * Get the BlockState of the block currently being broken.
     */
    private BlockState getBreakingBlockState(LocalPlayer player, Minecraft client) {
        if (client.hitResult instanceof net.minecraft.world.phys.BlockHitResult blockHit) {
            return player.level().getBlockState(blockHit.getBlockPos());
        }
        return null;
    }
}
