package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.slot.EnsureFreeInventorySlotTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.storage.ContainerType;
import adris.altoclef.util.helpers.ContainerHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;


public class LootContainerTask extends Task {
    public final BlockPos chest;
    public final List<Item> targets = new ArrayList<>();
    private final Predicate<ItemStack> check;
    private boolean weDoneHere = false;

    // Stuck detection: if quickMove fails silently (inventory full), bail out.
    // QUICK_MOVE does nothing when inventory is full — no cursor item, no error,
    // so the task loops forever finding the same slot and shift-clicking it.
    private static final int MAX_NO_PROGRESS_TICKS = 40; // ~2 seconds
    private int noProgressTicks = 0;
    private Slot lastAttemptedSlot = null;

    public LootContainerTask(BlockPos chestPos, List<Item> items) {
        chest = chestPos;
        targets.addAll(items);
        check = x -> true;
    }

    public LootContainerTask(BlockPos chestPos, List<Item> items, Predicate<ItemStack> pred) {
        chest = chestPos;
        targets.addAll(items);
        check = pred;
    }

    @Override
    protected void onStart() {
        AltoClef mod = AltoClef.getInstance();

        mod.getBehaviour().push();
        for (Item item : targets) {
            if (!mod.getBehaviour().isProtected(item)) {
                mod.getBehaviour().addProtectedItems(item);
            }
        }
        noProgressTicks = 0;
        lastAttemptedSlot = null;
    }

    @Override
    protected Task onTick() {
        if (!ContainerType.screenHandlerMatches(ContainerType.CHEST)) {
            setDebugState("Interact with container");
            return new InteractWithBlockTask(chest);
        }
        AltoClef mod = AltoClef.getInstance();

        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
        if (!cursor.isEmpty()) {
            noProgressTicks = 0; // cursor has an item, so something happened
            Optional<Slot> toFit = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursor, false);
            if (toFit.isPresent()) {
                setDebugState("Putting cursor in inventory");
                mod.getSlotHandler().clickSlot(toFit.get(), 0, SlotActionType.PICKUP);
                return null;
            } else {
                setDebugState("Ensuring space");
                return new EnsureFreeInventorySlotTask();
            }
        }
        Optional<Slot> optimal = getAMatchingSlot(mod);
        if (optimal.isEmpty()) {
            weDoneHere = true;
            return null;
        }

        // Check if we can actually fit this item before shift-clicking.
        // QUICK_MOVE silently does nothing when inventory is full.
        ItemStack slotStack = StorageHelper.getItemStackInSlot(optimal.get());
        if (!slotStack.isEmpty()) {
            Optional<Slot> canFit = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(slotStack, true);
            if (canFit.isEmpty()) {
                // No room at all — try to free space first
                setDebugState("Inventory full, making space");
                noProgressTicks++;
                if (noProgressTicks >= MAX_NO_PROGRESS_TICKS) {
                    Debug.logMessage("LootContainerTask: stuck for " + noProgressTicks
                            + " ticks, inventory full — giving up on remaining items");
                    weDoneHere = true;
                    return null;
                }
                return new EnsureFreeInventorySlotTask();
            }
        }

        // Stuck detection: if we keep trying the same slot, quickMove is failing
        if (lastAttemptedSlot != null && lastAttemptedSlot.equals(optimal.get())) {
            noProgressTicks++;
            if (noProgressTicks >= MAX_NO_PROGRESS_TICKS) {
                Debug.logMessage("LootContainerTask: stuck on slot " + optimal.get()
                        + " for " + noProgressTicks + " ticks — giving up");
                weDoneHere = true;
                return null;
            }
        } else {
            // Different slot = progress was made
            noProgressTicks = 0;
            lastAttemptedSlot = optimal.get();
        }

        setDebugState("Looting items: " + targets);
        ContainerHelper.quickMoveToPlayer(mod, optimal.get());
        return null;
    }

    @Override
    protected void onStop(Task task) {
        AltoClef mod = AltoClef.getInstance();
        StorageHelper.cleanupCursorSlot(mod);
        mod.getBehaviour().pop();
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof LootContainerTask lootContainerTask && targets.equals(lootContainerTask.targets) && chest.equals(lootContainerTask.chest);
    }

    private Optional<Slot> getAMatchingSlot(AltoClef mod) {
        for (Item item : targets) {
            List<Slot> slots = mod.getItemStorage().getSlotsWithItemContainer(item);
            if (!slots.isEmpty()) for (Slot slot : slots) {
                if (check.test(StorageHelper.getItemStackInSlot(slot))) return Optional.of(slot);
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean isFinished() {
        // weDoneHere alone is sufficient — don't require the container to still be open.
        // Old code required screenHandlerMatchesAny() which caused infinite reopening
        // when the player manually closed the chest or the server closed it.
        return weDoneHere;
    }

    @Override
    protected String toDebugString() {
        return "Looting a container";
    }
}
