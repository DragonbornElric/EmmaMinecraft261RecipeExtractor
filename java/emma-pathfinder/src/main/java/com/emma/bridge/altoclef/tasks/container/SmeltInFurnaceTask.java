package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
import adris.altoclef.BotBehaviour;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.resources.CollectFuelTask;
import adris.altoclef.tasks.slot.MoveInaccessibleItemToInventoryTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.SmeltTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.FurnaceSlot;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.screen.FurnaceScreenHandler;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;


// Ref
// https://minecraft.gamepedia.com/Smelting

/**
 * Smelt in a furnace, placing a furnace and collecting fuel as needed.
 */
public class SmeltInFurnaceTask extends ResourceTask {
    private final SmeltTarget[] _targets;

    private final DoSmeltInFurnaceTask _doTask;

    public SmeltInFurnaceTask(SmeltTarget[] targets) {
        super(extractItemTargets(targets));
        _targets = targets;
        // TODO: Do them in order.
        _doTask = new DoSmeltInFurnaceTask(targets[0]);
    }

    public SmeltInFurnaceTask(SmeltTarget target) {
        this(new SmeltTarget[]{target});
    }

    private static ItemTarget[] extractItemTargets(SmeltTarget[] recipeTargets) {
        List<ItemTarget> result = new ArrayList<>(recipeTargets.length);
        for (SmeltTarget target : recipeTargets) {
            result.add(target.getItem());
        }
        return result.toArray(ItemTarget[]::new);
    }

    public void ignoreMaterials() {
        _doTask.ignoreMaterials();
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        mod.getBehaviour().push();
        if (_targets.length != 1) {
            Debug.logWarning("Tried smelting multiple targets, only one target is supported at a time!");
        }
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        if (StorageHelper.itemTargetsMetInventoryNoCursor(itemTargets)) {
            _doTask.signalMainWorkDone();
        } else {
            Optional<BlockPos> furnacePos = mod.getBlockScanner().getNearestBlock(Blocks.FURNACE);
            furnacePos.ifPresent(blockPos -> mod.getBehaviour().avoidBlockBreaking(blockPos));
        }
        return _doTask;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        mod.getBehaviour().pop();
        StorageHelper.cleanupCursorSlot(mod);
    }

    @Override
    public boolean isFinished() {
        if (!super.isFinished() && !_doTask.isFinished()) return false;
        return _doTask.isRecoveryDone(AltoClef.getInstance());
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof SmeltInFurnaceTask task) {
            return task._doTask.isEqual(_doTask);
        }
        return false;
    }

    @Override
    protected String toDebugStringName() {
        return _doTask.toDebugString();
    }

    public SmeltTarget[] getTargets() {
        return _targets;
    }


    static class DoSmeltInFurnaceTask extends DoStuffInContainerTask {

        private final SmeltTarget target;
        private final SmeltingHelper.SmeltCache cache = new SmeltingHelper.SmeltCache();
        private final SmeltingHelper.FurnaceConfig config = new SmeltingHelper.FurnaceConfig(
                FurnaceSlot.INPUT_SLOT_MATERIALS, FurnaceSlot.INPUT_SLOT_FUEL, FurnaceSlot.OUTPUT_SLOT,
                StorageHelper::getFurnaceFuel, StorageHelper::getFurnaceCookPercent, "furnace");
        private final ItemTarget allMaterials;
        private boolean ignoreMaterials;

        public DoSmeltInFurnaceTask(SmeltTarget target) {
            super(Blocks.FURNACE, new ItemTarget(Items.FURNACE));
            this.target = target;
            allMaterials = new ItemTarget(Stream.concat(Arrays.stream(this.target.getMaterial().getMatches()), Arrays.stream(this.target.getOptionalMaterials())).toArray(Item[]::new), this.target.getMaterial().getTargetCount());
        }

        public void ignoreMaterials() {
            ignoreMaterials = true;
        }

        @Override
        protected boolean isSubTaskEqual(DoStuffInContainerTask other) {
            if (other instanceof DoSmeltInFurnaceTask task) {
                return task.target.equals(target) && task.ignoreMaterials == ignoreMaterials;
            }
            return false;
        }

        @Override
        protected boolean isContainerOpen(AltoClef mod) {
            return (mod.getPlayer().currentScreenHandler instanceof FurnaceScreenHandler);
        }

        @Override
        protected void onStart() {
            super.onStart();
            BotBehaviour botBehaviour = AltoClef.getInstance().getBehaviour();

            botBehaviour.addProtectedItems(ItemHelper.PLANKS);
            botBehaviour.addProtectedItems(Items.COAL);
            botBehaviour.addProtectedItems(allMaterials.getMatches());
            botBehaviour.addProtectedItems(target.getMaterial().getMatches());
        }

        @Override
        protected Task onTick() {
            AltoClef mod = AltoClef.getInstance();

            if (isContainerOpen(mod)) {
                SmeltingHelper.updateCache(cache, config);
            }

            int materialsNeeded = SmeltingHelper.calculateMaterialsNeeded(mod, target, allMaterials, cache);
            double fuelNeeded = SmeltingHelper.calculateFuelNeeded(mod, target, allMaterials, cache, ignoreMaterials);

            // We don't have enough materials...
            if (mod.getItemStorage().getItemCount(allMaterials.getMatches()) < materialsNeeded) {
                setDebugState("Getting Materials");
                return getMaterialTask(target.getMaterial());
            }

            // We don't have enough fuel...
            if (cache.burningFuelCount <= 0 && StorageHelper.calculateInventoryFuelCount(mod) < fuelNeeded) {
                setDebugState("Getting Fuel");
                return new CollectFuelTask(fuelNeeded + 1);
            }

            // Make sure our materials are accessible in our inventory
            if (StorageHelper.isItemInaccessibleToContainer(mod, allMaterials)) {
                return new MoveInaccessibleItemToInventoryTask(allMaterials);
            }

            // We have fuel and materials. Get to our container and smelt!
            return super.onTick();
        }

        // Override this if our materials must be acquired in a special way.
        // virtual
        protected Task getMaterialTask(ItemTarget target) {
            return TaskCatalogue.getItemTask(target);
        }

        @Override
        protected Task containerSubTask(AltoClef mod) {
            return SmeltingHelper.containerSubTask(mod, config, target, allMaterials, this::setDebugState);
        }

        @Override
        protected double getCostToMakeNew(AltoClef mod) {
            if (cache.burnPercentage > 0 || cache.burningFuelCount > 0 ||
                    !cache.fuelSlot.isEmpty() || !cache.materialSlot.isEmpty() ||
                    !cache.outputSlot.isEmpty()) {
                return 9999999.0;
            }
            if (mod.getItemStorage().getItemCount(Items.COBBLESTONE) > 8) {
                double cost = 100.0 - 90.0 * (double) mod.getItemStorage().getItemCount(new Item[]{Items.COBBLESTONE}) / 8.0;
                return Math.max(cost, 10.0);
            }
            return StorageHelper.miningRequirementMetInventory(MiningRequirement.WOOD) ? 50.0 : 100.0;
        }

        @Override
        protected BlockPos overrideContainerPosition(AltoClef mod) {
            return getTargetContainerPosition();
        }
    }
}
