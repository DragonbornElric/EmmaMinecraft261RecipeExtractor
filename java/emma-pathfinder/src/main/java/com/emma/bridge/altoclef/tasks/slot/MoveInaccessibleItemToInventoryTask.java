package adris.altoclef.tasks.slot;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ContainerHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.CursorSlot;
import adris.altoclef.util.slots.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;

import java.util.Objects;
import java.util.Optional;

public class MoveInaccessibleItemToInventoryTask extends Task {

    private final ItemTarget target;

    public MoveInaccessibleItemToInventoryTask(ItemTarget target) {
        this.target = target;
    }

    @Override
    protected void onStart() {

    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();

        // Ensure inventory is closed.
        if (!StorageHelper.isPlayerInventoryOpen()) {
            StorageHelper.cleanupCursorSlot(mod);
            setDebugState("Closing screen first");
            return null;
        }

        Optional<Slot> slotToMove = StorageHelper.getFilledInventorySlotInaccessibleToContainer(mod, target);
        if (slotToMove.isPresent()) {
            // Force cursor slot if we have one.
            if (target.matches(StorageHelper.getItemStackInCursorSlot().getItem())) {
                slotToMove = Optional.of(CursorSlot.SLOT);
            }
            // issue is a full cursor slot when trying to clear out bad items.
            // solution: ensure cursor is empty first
            if (!StorageHelper.getItemStackInCursorSlot().isEmpty()) {
                return new EnsureFreeCursorSlotTask();
            }

            Slot toMove = slotToMove.get();
            ItemStack stack = StorageHelper.getItemStackInSlot(toMove);
            Optional<Slot> toMoveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(stack, false);
            if (toMoveTo.isPresent()) {
                setDebugState("Moving slot " + toMove + " to inventory");
                if (Slot.isCursor(toMove)) {
                    // Cursor already holds the item — deposit into available slot
                    mod.getSlotHandler().clickSlot(toMoveTo.get(), 0, SlotActionType.PICKUP);
                } else {
                    // Shift-click directly to player inventory
                    ContainerHelper.quickMoveToPlayer(mod, toMove);
                }
                return null;
            } else {
                setDebugState("Free up inventory first.");
                // Make it free first.
                return new EnsureFreeInventorySlotTask();
            }
        }
        setDebugState("NONE FOUND");
        return null;
    }

    @Override
    protected void onStop(Task interruptTask) {

    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof MoveInaccessibleItemToInventoryTask task) {
            return Objects.equals(task.target, target);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Making item accessible: " + target;
    }
}
