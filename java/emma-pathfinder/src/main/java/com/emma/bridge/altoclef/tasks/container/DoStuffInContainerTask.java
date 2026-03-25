package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.construction.PlaceBlockNearbyTask;
import adris.altoclef.tasks.slot.EnsureFreeInventorySlotTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.BaritoneHelper;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.block.Block;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Arrays;
import java.util.Optional;


/**
 * Interacts with a container, obtaining and placing one if none were found nearby.
 */
public abstract class DoStuffInContainerTask extends Task {

    private final ItemTarget containerTarget;
    private final Block[] containerBlocks;

    private final PlaceBlockNearbyTask placeTask;
    // If we decided on placing, force place for at least 1 second
    // (originally 10)
    private final TimerGame placeForceTimer = new TimerGame(1);

    // If we just placed something, stop placing and try going to the nearest container.
    private final TimerGame justPlacedTimer = new TimerGame(3);
    private BlockPos cachedContainerPosition = null;
    private Task openTableTask;

    // Recovery: outer ResourceTask signals when item targets are met so we can pick up what we placed.
    private boolean _mainWorkDone = false;
    // Recovery timeout: 20s = 17.5s worst-case fist-break (hardness 3.5) + 2.5s buffer
    private final TimerGame recoveryTimeout = new TimerGame(20);
    private enum RecoveryPhase { IDLE, BREAKING, COLLECTING }
    private RecoveryPhase recoveryPhase = RecoveryPhase.IDLE;
    private int collectingTicks = 0;
    private boolean recoveryTimedOut = false;

    public DoStuffInContainerTask(Block[] containerBlocks, ItemTarget containerTarget) {
        this.containerBlocks = containerBlocks;
        this.containerTarget = containerTarget;

        placeTask = new PlaceBlockNearbyTask(this.containerBlocks);
    }

    public DoStuffInContainerTask(Block containerBlock, ItemTarget containerTarget) {
        this(new Block[]{containerBlock}, containerTarget);
    }

    @Override
    protected void onStart() {
        AltoClef mod = AltoClef.getInstance();
        mod.getBehaviour().push();
        if (openTableTask == null) {
            openTableTask = new DoToClosestBlockTask(InteractWithBlockTask::new, containerBlocks);
        }

        // Protect container since we might place it.
        mod.getBehaviour().addProtectedItems(ItemHelper.blocksToItems(containerBlocks));
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();

        // Recovery phase: outer task signaled work is done — break the container we placed,
        // then walk to the drop to trigger auto-pickup.
        if (_mainWorkDone && mod.getModSettings().shouldRecoverContainersAfterUse()) {
            BlockPos placed = getPlacedPosition();

            // No container was placed (used an existing one) — nothing to recover.
            // Leave _mainWorkDone true so isRecoveryDone() returns true via placed==null.
            if (placed == null) {
                return null;
            }

            boolean hasItem = mod.getItemStorage().hasItem(ItemHelper.blocksToItems(containerBlocks));

            // Phase 1: BREAKING — block still exists, break it
            if (placed != null
                    && mod.getBlockScanner().isBlockAtPosition(placed, containerBlocks)
                    && !hasItem) {
                // Skip recovery if inventory is full — breaking the block drops an item
                // entity we can't pick up, causing an infinite loop.
                // Try overflow first to free a slot before giving up.
                if (!mod.getItemStorage().hasEmptyInventorySlot()) {
                    try {
                        if (com.emma.overflow.OverflowClientMod.OverflowClientApi.isAvailable()) {
                            com.google.gson.JsonObject req = new com.google.gson.JsonObject();
                            req.addProperty("action", "free_slots");
                            req.addProperty("target", 1);

                            com.google.gson.JsonArray junkList = new com.google.gson.JsonArray();
                            for (net.minecraft.item.Item item : mod.getModSettings().getThrowawayItems(true)) {
                                junkList.add(net.minecraft.registry.Registries.ITEM.getId(item).toString());
                            }
                            req.add("junk_items", junkList);

                            java.util.Set<net.minecraft.item.Item> protectedItems = mod.getBehaviour().getProtectedItems();
                            if (!protectedItems.isEmpty()) {
                                com.google.gson.JsonArray protectedList = new com.google.gson.JsonArray();
                                for (net.minecraft.item.Item pItem : protectedItems) {
                                    protectedList.add(net.minecraft.registry.Registries.ITEM.getId(pItem).toString());
                                }
                                req.add("protected_items", protectedList);
                            }

                            com.emma.overflow.OverflowClientMod.OverflowClientApi.sendRequest(req);
                        }
                    } catch (NoClassDefFoundError ignored) {}
                    if (!mod.getItemStorage().hasEmptyInventorySlot()) {
                        Debug.logInternal("Skipping container recovery — inventory full after overflow attempt");
                        _mainWorkDone = false;
                        return null;
                    }
                }
                if (recoveryPhase == RecoveryPhase.IDLE) {
                    recoveryPhase = RecoveryPhase.BREAKING;
                    recoveryTimeout.reset();
                    mod.getBehaviour().allowBlockBreaking(placed);
                    Debug.logInternal("Container recovery started for " + placed.toShortString());
                }
                if (recoveryTimeout.elapsed()) {
                    Debug.logInternal("Container recovery timed out (breaking) for " + placed.toShortString() + " — skipping");
                    recoveryTimedOut = true;
                    recoveryPhase = RecoveryPhase.IDLE;
                    _mainWorkDone = false;
                    return null;
                }
                setDebugState("Recovering placed container (breaking)");
                return new DestroyBlockTask(placed);
            }

            // Phase 2: COLLECTING — block is gone, walk to drop position to auto-pickup
            if (placed != null && recoveryPhase == RecoveryPhase.BREAKING && !hasItem) {
                recoveryPhase = RecoveryPhase.COLLECTING;
                collectingTicks = 0;
                Debug.logInternal("Container broken, walking to drop at " + placed.toShortString());
            }
            if (recoveryPhase == RecoveryPhase.COLLECTING) {
                if (mod.getItemStorage().hasItem(ItemHelper.blocksToItems(containerBlocks))) {
                    Debug.logInternal("Container item collected");
                    recoveryPhase = RecoveryPhase.IDLE;
                    _mainWorkDone = false;
                    return null;
                }
                if (++collectingTicks > 3) {
                    Debug.logInternal("Container drop not collected after 3 ticks — giving up");
                    recoveryPhase = RecoveryPhase.IDLE;
                    _mainWorkDone = false;
                    return null;
                }
                setDebugState("Collecting dropped container");
                return new GetToBlockTask(placed);
            }

            // Recovery done (item obtained or no placed position) — reset so task can
            // re-enter work phase if outer targets aren't actually met yet.
            recoveryPhase = RecoveryPhase.IDLE;
            _mainWorkDone = false;
            // Fall through to normal work flow below
        }

        // If we're placing, keep on placing.
        if (mod.getItemStorage().hasItem(ItemHelper.blocksToItems(containerBlocks)) && placeTask.isActive() && !placeTask.isFinished()) {
            setDebugState("Placing container");
            return placeTask;
        }

        if (isContainerOpen(mod)) {
            return containerSubTask(mod);
        }

        // infinity if such a container does not exist.
        double costToWalk = Double.POSITIVE_INFINITY;

        Optional<BlockPos> nearest;

        Vec3d currentPos = mod.getPlayer().getPos();
        BlockPos override = overrideContainerPosition(mod);

        if (override != null && mod.getBlockScanner().isBlockAtPosition(override, containerBlocks)) {
            // We have an override so go there instead.
            nearest = Optional.of(override);
        } else {
            // Track nearest container
            nearest = mod.getBlockScanner().getNearestBlock(currentPos, blockPos -> WorldHelper.canReach(blockPos), containerBlocks);
        }
        if (nearest.isEmpty()) {
            // If all else fails, try using our placed task
            nearest = Optional.ofNullable(placeTask.getPlaced());
            if (nearest.isPresent() && !mod.getBlockScanner().isBlockAtPosition(nearest.get(), containerBlocks)) {
                nearest = Optional.empty();
            }
        }
        if (nearest.isPresent()) {
            costToWalk = BaritoneHelper.calculateGenericHeuristic(currentPos, WorldHelper.toVec3d(nearest.get()));
        }

        // Make a new container if going to the container is a pretty bad cost.
        // Also keep on making the container if we're stuck in some
        if (costToWalk > getCostToMakeNew(mod)) {
            placeForceTimer.reset();
        }
        if (nearest.isEmpty() || (!placeForceTimer.elapsed() && justPlacedTimer.elapsed())) {
            // It's cheaper to make a new one, or our only option.

            // We're no longer going to our previous container.
            cachedContainerPosition = null;

            // Get if we don't have...
            if (!mod.getItemStorage().hasItem(containerTarget)) {
                setDebugState("Getting container item");
                return TaskCatalogue.getItemTask(containerTarget);
            }

            setDebugState("Placing container...");

            justPlacedTimer.reset();
            // Now place!
            return placeTask;
        }

        // This is insanely cursed.
        // TODO: Finish committing to optionals, this is ugly.
        cachedContainerPosition = nearest.get();

        // Walk to it and open it

        // Wait for food
        if (mod.getFoodChain().needsToEat()) {
            setDebugState("Waiting for eating...");
            return null;
        }
        setDebugState("Walking to container... " + nearest.get().toShortString());

        if (!StorageHelper.getItemStackInCursorSlot().isEmpty()) {
            Optional<Slot> toMoveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(StorageHelper.getItemStackInCursorSlot(), false);
            if (toMoveTo.isEmpty()) {
                return new EnsureFreeInventorySlotTask();
            }
            if (ItemHelper.canThrowAwayStack(mod, StorageHelper.getItemStackInCursorSlot())) {
                mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
                return null;
            }
            mod.getSlotHandler().clickSlot(toMoveTo.get(), 0, SlotActionType.PICKUP);
            return null;
        }
        return openTableTask;
        //return new GetToBlockTask(nearest, true);
    }

    public ItemTarget getContainerTarget() {
        return containerTarget;
    }

    // Virtual
    protected BlockPos overrideContainerPosition(AltoClef mod) {
        return null;
    }

    protected BlockPos getTargetContainerPosition() {
        return cachedContainerPosition;
    }

    /**
     * Get the position where a container was placed by this task, or null if none was placed.
     */
    public BlockPos getPlacedPosition() {
        return placeTask.getPlaced();
    }

    /**
     * Called by the outer ResourceTask when its item targets are met.
     * Triggers the recovery phase: the bot will @get the container it placed.
     */
    public void signalMainWorkDone() {
        _mainWorkDone = true;
    }

    /**
     * Returns true when recovery is complete (or not needed).
     * Outer ResourceTask gates isFinished() on this.
     */
    public boolean isRecoveryDone(AltoClef mod) {
        if (!_mainWorkDone) return false;
        if (!mod.getModSettings().shouldRecoverContainersAfterUse()) return true;
        BlockPos placed = getPlacedPosition();
        if (placed == null) return true;
        if (recoveryTimedOut) return true;
        // Done only when item is in inventory — NOT just because block is gone.
        // The dropped item entity must be collected first.
        if (mod.getItemStorage().hasItem(ItemHelper.blocksToItems(containerBlocks))) return true;
        return false;
    }

    @Override
    protected void onStop(Task interruptTask) {
        StorageHelper.closeScreen();
        AltoClef.getInstance().getBehaviour().pop();
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof DoStuffInContainerTask task) {
            if (!Arrays.equals(task.containerBlocks, containerBlocks)) return false;
            if (!task.containerTarget.equals(containerTarget)) return false;
            return isSubTaskEqual(task);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Doing stuff in " + containerTarget + " container";
    }

    protected abstract boolean isSubTaskEqual(DoStuffInContainerTask other);

    protected abstract boolean isContainerOpen(AltoClef mod);

    protected abstract Task containerSubTask(AltoClef mod);

    protected abstract double getCostToMakeNew(AltoClef mod);
}
