package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.slots.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Generic container operations using QUICK_MOVE (shift-click).
 * <p>
 * The screen handler protocol is container-agnostic: every container interaction
 * is {@code clickSlot(syncId, slot, button, actionType, player)}. The container
 * type only determines which slot numbers mean what — that's configuration, not logic.
 * <p>
 * QUICK_MOVE is one packet, one tick, no cursor state — server auto-routes items
 * via {@code transferSlot()}. Use this for all standard container operations.
 * <p>
 * Slot layout definitions live in the existing Slot subclasses
 * (FurnaceSlot, BlastFurnaceSlot, SmokerSlot, etc.).
 */
public final class ContainerHelper {

    private ContainerHelper() {}

    /**
     * QUICK_MOVE a container slot → player inventory. Works for any container type.
     * Use for: retrieving output, recovering excess fuel, pulling items back out.
     * <p>
     * WARNING: QUICK_MOVE silently does nothing when player inventory is full.
     * Use {@link #canFitInPlayerInventory} to check before calling, or use
     * {@link #tryQuickMoveToPlayer} which checks automatically.
     *
     * @param mod           the AltoClef instance
     * @param containerSlot the slot to shift-click from
     */
    public static void quickMoveToPlayer(AltoClef mod, Slot containerSlot) {
        mod.getSlotHandler().clickSlot(containerSlot, 0, SlotActionType.QUICK_MOVE);
    }

    /**
     * Check whether the item in a container slot can fit in the player's inventory
     * (either via stacking or an empty slot). Use before {@link #quickMoveToPlayer}
     * to avoid silent failures when inventory is full.
     *
     * @param mod           the AltoClef instance
     * @param containerSlot the slot to check
     * @return true if at least a partial stack can be moved
     */
    public static boolean canFitInPlayerInventory(AltoClef mod, Slot containerSlot) {
        ItemStack stack = StorageHelper.getItemStackInSlot(containerSlot);
        if (stack.isEmpty()) return false;
        return mod.getItemStorage().getSlotThatCanFitInPlayerInventory(stack, true).isPresent();
    }

    /**
     * QUICK_MOVE with inventory-full guard. Returns false if the item can't fit,
     * allowing the caller to take corrective action (e.g. free inventory space).
     *
     * @param mod           the AltoClef instance
     * @param containerSlot the slot to shift-click from
     * @return true if the move was attempted (inventory had space), false if skipped
     */
    public static boolean tryQuickMoveToPlayer(AltoClef mod, Slot containerSlot) {
        if (!canFitInPlayerInventory(mod, containerSlot)) return false;
        quickMoveToPlayer(mod, containerSlot);
        return true;
    }

    /**
     * QUICK_MOVE an item from player inventory → container.
     * Server auto-routes to the correct container slot via transferSlot().
     * Prefers the largest stack to minimize round-trips.
     *
     * @param mod  the AltoClef instance
     * @param item the item to move (uses item type matches and target count)
     * @return the inventory Slot used, or null if no matching slot found
     */
    @Nullable
    public static Slot quickMoveToContainer(AltoClef mod, ItemTarget item) {
        List<Slot> slots = mod.getItemStorage()
                .getSlotsWithItemPlayerInventory(false, item.getMatches());
        if (slots.isEmpty()) return null;

        // Pick largest stack to minimize operations
        Slot best = slots.get(0);
        int bestCount = StorageHelper.getItemStackInSlot(best).getCount();
        for (int i = 1; i < slots.size(); i++) {
            int count = StorageHelper.getItemStackInSlot(slots.get(i)).getCount();
            if (count > bestCount) {
                best = slots.get(i);
                bestCount = count;
            }
        }
        mod.getSlotHandler().clickSlot(best, 0, SlotActionType.QUICK_MOVE);
        return best;
    }

    /**
     * QUICK_MOVE all non-empty items from the given container slots → player inventory.
     * Use for: emptying a container, bulk output retrieval.
     *
     * @param mod            the AltoClef instance
     * @param containerSlots the slots to drain
     */
    public static void quickMoveAllToPlayer(AltoClef mod, Slot... containerSlots) {
        for (Slot slot : containerSlots) {
            ItemStack stack = StorageHelper.getItemStackInSlot(slot);
            if (!stack.isEmpty()) {
                quickMoveToPlayer(mod, slot);
            }
        }
    }
}
