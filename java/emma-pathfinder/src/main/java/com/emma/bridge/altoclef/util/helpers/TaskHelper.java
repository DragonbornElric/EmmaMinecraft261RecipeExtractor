package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.movement.SafeRandomShimmyTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.control.DirectInput;
import net.minecraft.block.*;
import adris.altoclef.multiversion.versionedfields.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;

import java.util.Optional;

/**
 * Shared utilities for task stuck-detection, obstacle handling, and common cleanup.
 * Extracted from duplicated code across InteractWithBlockTask, DestroyBlockTask, and TimeoutWanderTask.
 */
public final class TaskHelper {

    public static final Block[] ANNOYING_BLOCKS = {
            Blocks.VINE,
            Blocks.NETHER_SPROUTS,
            Blocks.CAVE_VINES,
            Blocks.CAVE_VINES_PLANT,
            Blocks.TWISTING_VINES,
            Blocks.TWISTING_VINES_PLANT,
            Blocks.WEEPING_VINES_PLANT,
            Blocks.LADDER,
            Blocks.BIG_DRIPLEAF,
            Blocks.BIG_DRIPLEAF_STEM,
            Blocks.SMALL_DRIPLEAF,
            Blocks.TALL_GRASS,
            Blocks.SHORT_GRASS,
            Blocks.SWEET_BERRY_BUSH
    };

    private TaskHelper() {}

    public static BlockPos[] generateSides(BlockPos pos) {
        return new BlockPos[]{
                pos.add(1, 0, 0),
                pos.add(-1, 0, 0),
                pos.add(0, 0, 1),
                pos.add(0, 0, -1),
                pos.add(1, 0, -1),
                pos.add(1, 0, 1),
                pos.add(-1, 0, -1),
                pos.add(-1, 0, 1)
        };
    }

    /**
     * Check if a block at the given position is an obstacle that can get the player stuck.
     * Checks both the annoying blocks array AND instanceof checks for doors, fences, etc.
     */
    public static boolean isAnnoying(AltoClef mod, BlockPos pos) {
        Block block = mod.getWorld().getBlockState(pos).getBlock();
        if (block instanceof DoorBlock || block instanceof FenceBlock
                || block instanceof FenceGateBlock || block instanceof FlowerBlock) {
            return true;
        }
        for (Block annoyingBlock : ANNOYING_BLOCKS) {
            if (block == annoyingBlock) return true;
        }
        return false;
    }

    /**
     * Find a block position where the player is stuck in an annoying block.
     * Checks the player's position, one block up, and all 8 cardinal+diagonal neighbors at both levels.
     */
    public static BlockPos stuckInBlock(AltoClef mod) {
        BlockPos p = mod.getPlayer().getBlockPos();
        if (isAnnoying(mod, p)) return p;
        if (isAnnoying(mod, p.up())) return p.up();
        for (BlockPos check : generateSides(p)) {
            if (isAnnoying(mod, check)) return check;
        }
        for (BlockPos check : generateSides(p.up())) {
            if (isAnnoying(mod, check)) return check;
        }
        return null;
    }

    public static Task getUnstuckTask() {
        return new SafeRandomShimmyTask();
    }

    /**
     * Handle nether portal escape by holding sneak+forward when stuck and not pathing.
     * Returns true if the player is in a nether portal (caller should return null from onTick).
     * Returns false if not in a portal.
     */
    public static boolean handleNetherPortalEscape(AltoClef mod) {
        if (WorldHelper.isInNetherPortal()) {
            if (!mod.getClientBaritone().getPathingBehavior().isPathing()) {
                DirectInput.setSneaking(true);
                DirectInput.setForward(true);
                return true;
            } else {
                DirectInput.setSneaking(false);
                DirectInput.setBack(false);
                DirectInput.setForward(false);
            }
        } else if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
            DirectInput.setSneaking(false);
            DirectInput.setBack(false);
            DirectInput.setForward(false);
        }
        return false;
    }

    /**
     * Clean up cursor slot items properly, with early returns to avoid double-actions.
     * Tries in order: fit in inventory → throw if garbage → swap with garbage slot → drop.
     */
    public static void cleanupCursorSlot(AltoClef mod) {
        ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();
        if (!cursorStack.isEmpty()) {
            Optional<Slot> moveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursorStack, false);
            if (moveTo.isPresent()) {
                mod.getSlotHandler().clickSlot(moveTo.get(), 0, SlotActionType.PICKUP);
                return;
            }
            if (ItemHelper.canThrowAwayStack(mod, cursorStack)) {
                mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
                return;
            }
            Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
            if (garbage.isPresent()) {
                mod.getSlotHandler().clickSlot(garbage.get(), 0, SlotActionType.PICKUP);
                return;
            }
            mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
        } else {
            StorageHelper.closeScreen();
        }
    }
}
