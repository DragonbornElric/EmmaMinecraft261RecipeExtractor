package adris.altoclef.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.BotBehaviour;
import adris.altoclef.multiversion.blockpos.BlockPosVer;
import adris.altoclef.tasks.container.PickupFromContainerTask;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasks.resources.MineAndCollectTask;
import adris.altoclef.tasks.slot.EnsureFreePlayerCraftingGridTask;
import adris.altoclef.tasks.slot.MoveInaccessibleItemToInventoryTask;
import adris.altoclef.tasks.slot.WithdrawFromOverflowTask;
import adris.altoclef.tasksystem.ITaskCanForce;
import adris.altoclef.tasksystem.ITaskUsesCraftingGrid;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.storage.ContainerCache;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StlHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import net.minecraft.block.Block;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;
import org.apache.commons.lang3.ArrayUtils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.registry.Registries;

import java.util.*;

/**
 * The parent for all "collect an item" tasks.
 * <p>
 * If the target item is on the ground or in a chest, will grab from those sources first.
 */
public abstract class ResourceTask extends Task implements ITaskCanForce {

    protected final ItemTarget[] itemTargets;

    private final PickupDroppedItemTask pickupTask;
    private final EnsureFreePlayerCraftingGridTask ensureFreeCraftingGridTask = new EnsureFreePlayerCraftingGridTask();
    private ContainerCache currentContainer;
    // Extra resource parameters
    private Block[] mineIfPresent = null;
    private boolean forceDimension = false;
    private boolean allowContainers = false;
    private boolean craftOnly = false;
    private Dimension targetDimension;
    private BlockPos mineLastClosest = null;
    private int overflowCheckTick = -1;
    private static final int OVERFLOW_FIRST_CHECK_DELAY = 3;   // ticks before first check
    private static final int OVERFLOW_RECHECK_INTERVAL = 200;  // re-check every ~10 seconds

    public ResourceTask(ItemTarget[] itemTargets) {
        this.itemTargets = itemTargets;
        pickupTask = new PickupDroppedItemTask(this.itemTargets, true);
    }

    public ResourceTask(ItemTarget target) {
        this(new ItemTarget[]{target});
    }

    public ResourceTask(Item item, int targetCount) {
        this(new ItemTarget(item, targetCount));
    }

    @Override
    public boolean isFinished() {
        return StorageHelper.itemTargetsMetInventoryNoCursor(itemTargets);
    }

    @Override
    public boolean shouldForce(Task interruptingCandidate) {
        // We have an important item target in our cursor.
        return StorageHelper.itemTargetsMetInventory(itemTargets) && !isFinished()
                // This _should_ be redundant, but it'll be a guard just to make 100% sure.
                && Arrays.stream(itemTargets).anyMatch(target -> target.matches(StorageHelper.getItemStackInCursorSlot().getItem()));
    }

    @Override
    protected void onStart() {
        BotBehaviour botBehaviour = AltoClef.getInstance().getBehaviour();

        botBehaviour.push();
        //removeThrowawayItems(_itemTargets);
        botBehaviour.addProtectedItems(ItemTarget.getMatches(itemTargets));

        onResourceStart(AltoClef.getInstance());

        // Refresh overflow cache (fire-and-forget, don't wait for response)
        overflowCheckTick = -1;
        try {
            if (com.emma.overflow.OverflowClientMod.OverflowClientApi.isAvailable()) {
                com.emma.overflow.OverflowClientMod.OverflowClientApi.sendAction("status");
            }
        } catch (NoClassDefFoundError ignored) {}
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();

        // If we have an item in an INACCESSIBLE inventory slot
        if (!(thisOrChildSatisfies(task -> task instanceof ITaskUsesCraftingGrid)) || ensureFreeCraftingGridTask.isActive()) {
            for (ItemTarget target : itemTargets) {
                if (StorageHelper.isItemInaccessibleToContainer(mod, target)) {
                    setDebugState("Moving from SPECIAL inventory slot");
                    return new MoveInaccessibleItemToInventoryTask(target);
                }
            }
        }
        // We have enough items COUNTING the cursor slot, we just need to move an item from our cursor.
        if (StorageHelper.itemTargetsMetInventory(itemTargets) && Arrays.stream(itemTargets).anyMatch(target -> target.matches(StorageHelper.getItemStackInCursorSlot().getItem()))) {
            setDebugState("Moving from cursor");
            Optional<Slot> moveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(StorageHelper.getItemStackInCursorSlot(), false);
            if (moveTo.isPresent()) {
                mod.getSlotHandler().clickSlot(moveTo.get(), 0, SlotActionType.PICKUP);
                return null;
            }
            if (ItemHelper.canThrowAwayStack(mod, StorageHelper.getItemStackInCursorSlot())) {
                mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
                return null;
            }
            Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
            // Try throwing away cursor slot if it's garbage
            if (garbage.isPresent()) {
                mod.getSlotHandler().clickSlot(garbage.get(), 0, SlotActionType.PICKUP);
                return null;
            }
            mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
            return null;
        }

        // ── Overflow auto-withdraw: check periodically, not just once ──
        {
            overflowCheckTick++;
            boolean shouldCheck = false;

            if (overflowCheckTick == OVERFLOW_FIRST_CHECK_DELAY) {
                // First check — status response from onStart() should be cached by now
                shouldCheck = true;
            } else if (overflowCheckTick > OVERFLOW_FIRST_CHECK_DELAY
                       && (overflowCheckTick - OVERFLOW_FIRST_CHECK_DELAY) % OVERFLOW_RECHECK_INTERVAL == 0) {
                // Periodic re-check — catches items deposited mid-task
                shouldCheck = true;
                // Refresh the cache for the NEXT re-check
                try {
                    if (com.emma.overflow.OverflowClientMod.OverflowClientApi.isAvailable()) {
                        com.emma.overflow.OverflowClientMod.OverflowClientApi.sendAction("status");
                    }
                } catch (NoClassDefFoundError ignored) {}
            }

            if (shouldCheck) {
                try {
                    if (com.emma.overflow.OverflowClientMod.OverflowClientApi.isAvailable()) {
                        JsonObject cached = com.emma.overflow.OverflowClientMod.OverflowClientApi.getCachedStatus();
                        if (cached != null && cached.has("items")) {
                            Map<String, Integer> overflowMap = new HashMap<>();
                            for (JsonElement elem : cached.getAsJsonArray("items")) {
                                JsonObject obj = elem.getAsJsonObject();
                                overflowMap.put(obj.get("item").getAsString(), obj.get("count").getAsInt());
                            }
                            boolean anyAvailable = false;
                            for (ItemTarget target : itemTargets) {
                                int have = mod.getItemStorage().getItemCountInventoryOnly(target.getMatches());
                                if (have >= target.getTargetCount()) continue;
                                for (Item match : target.getMatches()) {
                                    String id = Registries.ITEM.getId(match).toString();
                                    if (overflowMap.getOrDefault(id, 0) > 0) {
                                        anyAvailable = true;
                                        break;
                                    }
                                }
                                if (anyAvailable) break;
                            }
                            if (anyAvailable) {
                                setDebugState("Withdrawing from overflow");
                                return new WithdrawFromOverflowTask(itemTargets);
                            }
                        }
                    }
                } catch (NoClassDefFoundError ignored) {}
            }
        }

        if (!craftOnly && !shouldAvoidPickingUp(mod)) {
            // Check if items are on the floor. If so, pick em up.
            if (mod.getEntityTracker().itemDropped(itemTargets)) {

                // If we're picking up a pickaxe (we can't go far underground or mine much)
                if (PickupDroppedItemTask.isIsGettingPickaxeFirst(mod)) {
                    if (pickupTask.isCollectingPickaxeForThis()) {
                        setDebugState("Picking up (pickaxe first!)");
                        // Our pickup task is the one collecting the pickaxe, keep it going.
                        return pickupTask;
                    }
                    // Only get items that are CLOSE to us.
                    Optional<ItemEntity> closest = mod.getEntityTracker().getClosestItemDrop(mod.getPlayer().getPos(), itemTargets);
                    if (closest.isPresent() && !closest.get().isInRange(mod.getPlayer(), 10)) {
                        return onResourceTick(mod);
                    }
                }

                double range = getPickupRange(mod);
                Optional<ItemEntity> closest = mod.getEntityTracker().getClosestItemDrop(mod.getPlayer().getPos(), itemTargets);
                if (range < 0 || (closest.isPresent() && closest.get().isInRange(mod.getPlayer(), range)) || (pickupTask.isActive() && !pickupTask.isFinished())) {
                    setDebugState("Picking up");
                    return pickupTask;
                }
            }
        }

        // Check for chests and grab resources from them.
        if (currentContainer == null && allowContainers) {
            List<ContainerCache> containersWithItem = mod.getItemStorage().getContainersWithItem(Arrays.stream(itemTargets).reduce(new Item[0], (items, target) -> ArrayUtils.addAll(items, target.getMatches()), ArrayUtils::addAll));
            if (!containersWithItem.isEmpty()) {
                ContainerCache closest = containersWithItem.stream().min(StlHelper.compareValues(container -> BlockPosVer.getSquaredDistance(container.getBlockPos(),mod.getPlayer().getPos()))).get();
                if (closest.getBlockPos().isWithinDistance(mod.getPlayer().getPos(), mod.getModSettings().getResourceChestLocateRange())) {
                    currentContainer = closest;
                }
            }
        }
        if (currentContainer != null) {
            Optional<ContainerCache> container = mod.getItemStorage().getContainerAtPosition(currentContainer.getBlockPos());
            if (container.isPresent()) {
                if (Arrays.stream(itemTargets).noneMatch(target -> container.get().hasItem(target.getMatches()))) {
                    currentContainer = null;
                } else {
                    // We have a current chest, grab from it.
                    setDebugState("Picking up from container");
                    return new PickupFromContainerTask(currentContainer.getBlockPos(), itemTargets);
                }
            } else {
                currentContainer = null;
            }
        }

        // We may just mine if a block is found.
        if (mineIfPresent != null && !craftOnly) {
            ArrayList<Block> satisfiedReqs = new ArrayList<>(Arrays.asList(mineIfPresent));
            satisfiedReqs.removeIf(block -> !StorageHelper.miningRequirementMet(MiningRequirement.getMinimumRequirementForBlock(block)));
            if (!satisfiedReqs.isEmpty()) {
                if (mod.getBlockScanner().anyFound(satisfiedReqs.toArray(Block[]::new))) {
                    Optional<BlockPos> closest = mod.getBlockScanner().getNearestBlock(mineIfPresent);
                    if (closest.isPresent() && closest.get().isWithinDistance(mod.getPlayer().getPos(), mod.getModSettings().getResourceMineRange())) {
                        mineLastClosest = closest.get();
                    }
                    if (mineLastClosest != null) {
                        if (mineLastClosest.isWithinDistance(mod.getPlayer().getPos(), mod.getModSettings().getResourceMineRange() * 1.5 + 20)) {
                            return new MineAndCollectTask(itemTargets, mineIfPresent, MiningRequirement.HAND);
                        }
                    }
                }
            }
        }
        // Make sure that items don't get stuck in the player crafting grid. May be an issue if a future task isn't a resource task.
        if (StorageHelper.isPlayerInventoryOpen()) {
            if (!(thisOrChildSatisfies(task -> task instanceof ITaskUsesCraftingGrid)) || ensureFreeCraftingGridTask.isActive()) {
                for (Slot slot : PlayerSlot.CRAFT_INPUT_SLOTS) {
                    if (!StorageHelper.getItemStackInSlot(slot).isEmpty()) {
                        return ensureFreeCraftingGridTask;
                    }
                }
            }
        }
        return onResourceTick(mod);
    }

    protected double getPickupRange(AltoClef mod) {
        return mod.getModSettings().getResourcePickupRange();
    }

    @Override
    protected void onStop(Task interruptTask) {
        AltoClef.getInstance().getBehaviour().pop();
        onResourceStop(AltoClef.getInstance(), interruptTask);
    }

    @Override
    protected boolean isEqual(Task other) {
        // Same target items
        if (other instanceof ResourceTask t) {
            if (!isEqualResource(t)) return false;
            return Arrays.equals(t.itemTargets, itemTargets);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        StringBuilder result = new StringBuilder();
        result.append(toDebugStringName()).append(": [");
        int c = 0;
        if (itemTargets != null) {
            for (ItemTarget target : itemTargets) {
                result.append(target != null ? target.toString() : "(null)");
                if (++c != itemTargets.length) {
                    result.append(", ");
                }
            }
        }
        result.append("]");
        return result.toString();
    }

    protected boolean isInWrongDimension(AltoClef mod) {
        if (forceDimension) {
            return WorldHelper.getCurrentDimension() != targetDimension;
        }
        return false;
    }

    protected Task getToCorrectDimensionTask(AltoClef mod) {
        return new DefaultGoToDimensionTask(targetDimension);
    }

    public ResourceTask mineIfPresent(Block[] toMine) {
        mineIfPresent = toMine;
        return this;
    }

    public ResourceTask forceDimension(Dimension dimension) {
        forceDimension = true;
        targetDimension = dimension;
        return this;
    }

    public ResourceTask craftOnly() {
        craftOnly = true;
        return this;
    }

    public void setAllowContainers(boolean value) {
        this.allowContainers = value;
    }

    public boolean getAllowContainers() {
        return allowContainers;
    }

    protected abstract boolean shouldAvoidPickingUp(AltoClef mod);

    protected abstract void onResourceStart(AltoClef mod);

    protected abstract Task onResourceTick(AltoClef mod);

    protected abstract void onResourceStop(AltoClef mod, Task interruptTask);

    protected abstract boolean isEqualResource(ResourceTask other);

    protected abstract String toDebugStringName();

    public ItemTarget[] getItemTargets() {
        return itemTargets;
    }
}
