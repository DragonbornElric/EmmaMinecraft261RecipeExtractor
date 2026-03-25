package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.multiversion.versionedfields.Blocks;
import adris.altoclef.multiversion.versionedfields.Items;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.resources.CollectFuelTask;
import adris.altoclef.tasks.slot.MoveInaccessibleItemToInventoryTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.SmeltTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.BlastFurnaceSlot;
import net.minecraft.item.Item;
import net.minecraft.screen.BlastFurnaceScreenHandler;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;


// Ref
// https://minecraft.gamepedia.com/Smelting

/**
 * Smelt in a blast furnace, placing a blast furnace and collecting fuel as needed.
 */
public class SmeltInBlastFurnaceTask extends ResourceTask {

    private final SmeltTarget[] _targets;

    private final DoSmeltInBlastFurnaceTask _doTask;

    public SmeltInBlastFurnaceTask(SmeltTarget[] targets) {
        super(extractItemTargets(targets));
        _targets = targets;
        // TODO: Do them in order.
        _doTask = new DoSmeltInBlastFurnaceTask(targets[0]);
    }

    public SmeltInBlastFurnaceTask(SmeltTarget target) {
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
            Optional<BlockPos> blastFurnacePos = mod.getBlockScanner().getNearestBlock(Blocks.BLAST_FURNACE);
            blastFurnacePos.ifPresent(blockPos -> mod.getBehaviour().avoidBlockBreaking(blockPos));
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
        if (other instanceof SmeltInBlastFurnaceTask task) {
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

    static class DoSmeltInBlastFurnaceTask extends DoStuffInContainerTask {

        private final SmeltTarget _target;
        private final SmeltingHelper.SmeltCache cache = new SmeltingHelper.SmeltCache();
        private final SmeltingHelper.FurnaceConfig config = new SmeltingHelper.FurnaceConfig(
                BlastFurnaceSlot.INPUT_SLOT_MATERIALS, BlastFurnaceSlot.INPUT_SLOT_FUEL, BlastFurnaceSlot.OUTPUT_SLOT,
                StorageHelper::getBlastFurnaceFuel, StorageHelper::getBlastFurnaceCookPercent, "blast_furnace");
        private final ItemTarget _allMaterials;
        private boolean _ignoreMaterials;

        public DoSmeltInBlastFurnaceTask(SmeltTarget target) {
            super(Blocks.BLAST_FURNACE, new ItemTarget(Items.BLAST_FURNACE));
            _target = target;
            _allMaterials = new ItemTarget(Stream.concat(Arrays.stream(_target.getMaterial().getMatches()), Arrays.stream(_target.getOptionalMaterials())).toArray(Item[]::new), _target.getMaterial().getTargetCount());
        }

        public void ignoreMaterials() {
            _ignoreMaterials = true;
        }

        @Override
        protected boolean isSubTaskEqual(DoStuffInContainerTask other) {
            if (other instanceof DoSmeltInBlastFurnaceTask task) {
                return task._target.equals(_target) && task._ignoreMaterials == _ignoreMaterials;
            }
            return false;
        }

        @Override
        protected boolean isContainerOpen(AltoClef mod) {
            return (mod.getPlayer().currentScreenHandler instanceof BlastFurnaceScreenHandler);
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
            if (mod.getItemStorage().getItemCount(Items.COBBLESTONE) > 11 &&
                    mod.getItemStorage().getItemCount(Items.RAW_IRON) > 5) {
                double cost = 100.0 - 90.0 * (((double) mod.getItemStorage().getItemCount(new Item[]{Items.COBBLESTONE})
                        / 8.0) + ((double) mod.getItemStorage().getItemCount(Items.RAW_IRON) / 5.0));
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
