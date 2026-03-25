package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
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
import adris.altoclef.util.slots.SmokerSlot;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.screen.SmokerScreenHandler;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;


// Ref
// https://minecraft.gamepedia.com/Smelting

/**
 * Smelt in a smoker, placing a smoker and collecting fuel as needed.
 */
public class SmeltInSmokerTask extends ResourceTask {

    private final SmeltTarget target;

    private final DoSmeltInSmokerTask doTask;

    public SmeltInSmokerTask(SmeltTarget target) {
        super(extractItemTargets(new SmeltTarget[]{target}));
        this.target = target;
        // TODO: Do them in order.
        boolean ignoreMaterials = false;
        doTask = new DoSmeltInSmokerTask(target, ignoreMaterials);
    }



    private static ItemTarget[] extractItemTargets(SmeltTarget[] recipeTargets) {
        List<ItemTarget> result = new ArrayList<>(recipeTargets.length);
        for (SmeltTarget target : recipeTargets) {
            result.add(target.getItem());
        }
        return result.toArray(ItemTarget[]::new);
    }

    public void ignoreMaterials() {
        doTask.ignoreMaterials();
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        mod.getBehaviour().push();
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        if (StorageHelper.itemTargetsMetInventoryNoCursor(itemTargets)) {
            doTask.signalMainWorkDone();
        } else {
            Optional<BlockPos> smokerPos = mod.getBlockScanner().getNearestBlock(Blocks.SMOKER);
            smokerPos.ifPresent(blockPos -> mod.getBehaviour().avoidBlockBreaking(blockPos));
        }
        return doTask;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        mod.getBehaviour().pop();
        StorageHelper.cleanupCursorSlot(mod);
    }

    @Override
    public boolean isFinished() {
        if (!super.isFinished() && !doTask.isFinished()) return false;
        return doTask.isRecoveryDone(AltoClef.getInstance());
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof SmeltInSmokerTask task) {
            return task.doTask.isEqual(doTask);
        }
        return false;
    }

    @Override
    protected String toDebugStringName() {
        return doTask.toDebugString();
    }

    public SmeltTarget[] getTargets() {
        return new SmeltTarget[]{target};
    }

    static class DoSmeltInSmokerTask extends DoStuffInContainerTask {

        private final SmeltTarget _target;
        private final SmeltingHelper.SmeltCache cache = new SmeltingHelper.SmeltCache();
        private final SmeltingHelper.FurnaceConfig config = new SmeltingHelper.FurnaceConfig(
                SmokerSlot.INPUT_SLOT_MATERIALS, SmokerSlot.INPUT_SLOT_FUEL, SmokerSlot.OUTPUT_SLOT,
                StorageHelper::getSmokerFuel, StorageHelper::getSmokerCookPercent, "smoker");
        private final ItemTarget _allMaterials;
        private boolean _ignoreMaterials;

        public DoSmeltInSmokerTask(SmeltTarget target, boolean ignoreMaterials) {
            super(Blocks.SMOKER, new ItemTarget(Items.SMOKER));
            _target = target;
            _ignoreMaterials = ignoreMaterials;
            _allMaterials = new ItemTarget(Stream.concat(Arrays.stream(_target.getMaterial().getMatches()), Arrays.stream(_target.getOptionalMaterials())).toArray(Item[]::new), _target.getMaterial().getTargetCount());
        }

        public void ignoreMaterials() {
            _ignoreMaterials = true;
        }

        @Override
        protected boolean isSubTaskEqual(DoStuffInContainerTask other) {
            if (other instanceof DoSmeltInSmokerTask task) {
                return task._target.equals(_target) && task._ignoreMaterials == _ignoreMaterials;
            }
            return false;
        }

        @Override
        protected boolean isContainerOpen(AltoClef mod) {
            return (mod.getPlayer().currentScreenHandler instanceof SmokerScreenHandler);
        }

        @Override
        protected void onStart() {
            super.onStart();
            AltoClef mod = AltoClef.getInstance();

            mod.getBehaviour().addProtectedItems(ItemHelper.PLANKS);
            mod.getBehaviour().addProtectedItems(Items.COAL);
            mod.getBehaviour().addProtectedItems(_allMaterials.getMatches());
            mod.getBehaviour().addProtectedItems(_target.getMaterial().getMatches());
        }

        @Override
        protected Task onTick() {
            AltoClef mod = AltoClef.getInstance();

            if (isContainerOpen(mod)) {
                SmeltingHelper.updateCache(cache, config);
            }

            int materialsNeeded = SmeltingHelper.calculateMaterialsNeeded(mod, _target, _allMaterials, cache);
            double fuelNeeded = SmeltingHelper.calculateFuelNeeded(mod, _target, _allMaterials, cache, _ignoreMaterials);

            if (mod.getItemStorage().getItemCount(_allMaterials.getMatches()) < materialsNeeded) {
                setDebugState("Getting Materials");
                return getMaterialTask(_target.getMaterial());
            }

            if (cache.burningFuelCount <= 0 && StorageHelper.calculateInventoryFuelCount(mod) < fuelNeeded) {
                setDebugState("Getting Fuel");
                return new CollectFuelTask(fuelNeeded + 1);
            }

            if (StorageHelper.isItemInaccessibleToContainer(mod, _allMaterials)) {
                return new MoveInaccessibleItemToInventoryTask(_allMaterials);
            }

            return super.onTick();
        }

        protected Task getMaterialTask(ItemTarget target) {
            return TaskCatalogue.getItemTask(target);
        }

        @Override
        protected Task containerSubTask(AltoClef mod) {
            return SmeltingHelper.containerSubTask(mod, config, _target, _allMaterials, this::setDebugState);
        }

        @Override
        protected double getCostToMakeNew(AltoClef mod) {
            if (cache.burnPercentage > 0 || cache.burningFuelCount > 0 ||
                    !cache.fuelSlot.isEmpty() || !cache.materialSlot.isEmpty() ||
                    !cache.outputSlot.isEmpty()) {
                return 9999999.0;
            }
            if (mod.getItemStorage().getItemCount(Items.COBBLESTONE) > 8 &&
                    mod.getItemStorage().getItemCount(ItemHelper.LOG) > 4) {
                double cost = 100.0 - 90.0 * (((double) mod.getItemStorage().getItemCount(new Item[]{Items.COBBLESTONE})
                        / 8.0) + ((double) mod.getItemStorage().getItemCount(ItemHelper.LOG) / 4.0));
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
