package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.slot.EnsureFreeInventorySlotTask;
import adris.altoclef.tasks.slot.MoveItemToSlotFromInventoryTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.util.slots.SmithingTableSlot;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.SmithingScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import java.util.ArrayList;
import java.util.List;



public class UpgradeInSmithingTableTask extends ResourceTask {

    private final ItemTarget _tool;
    private final ItemTarget _template;
    private final ItemTarget _material;
    private final ItemTarget _output;

    private final Task _innerTask;

    public UpgradeInSmithingTableTask(ItemTarget tool, ItemTarget material, ItemTarget output) {
        super(output);
        _tool = new ItemTarget(tool, output.getTargetCount());
        _material = new ItemTarget(material, output.getTargetCount());
        _template = new ItemTarget("netherite_upgrade_smithing_template", output.getTargetCount());
        _output = output;
        _innerTask = new UpgradeInSmithingTableInternalTask();
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
    }

    private int getItemsInSlot(Slot slot, ItemTarget match) {
        ItemStack stack = StorageHelper.getItemStackInSlot(slot);
        if (!stack.isEmpty() && match.matches(stack.getItem())) {
            return stack.getCount();
        }
        return 0;
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        // if we don't have tools + materials, get them.

        boolean inSmithingTable = (mod.getPlayer().currentScreenHandler instanceof SmithingScreenHandler);

        int templatesInSlot = inSmithingTable ? getItemsInSlot(SmithingTableSlot.INPUT_SLOT_TEMPLATE, _template) : 0;
        int materialsInSlot = inSmithingTable ? getItemsInSlot(SmithingTableSlot.INPUT_SLOT_MATERIALS, _material) : 0;
        int toolsInSlot = inSmithingTable ? getItemsInSlot(SmithingTableSlot.INPUT_SLOT_TOOL, _tool) : 0;
        int ouputInSlot = inSmithingTable ? getItemsInSlot(SmithingTableSlot.OUTPUT_SLOT, _output) : 0;

        int desiredOutput = _output.getTargetCount() - ouputInSlot;

        // Count equipped armor toward tool availability — don't re-craft what we're wearing
        int equippedToolCount = StorageHelper.isArmorEquipped(_tool.getMatches()) ? 1 : 0;
        boolean needTool = mod.getItemStorage().getItemCount(_tool) + toolsInSlot + equippedToolCount < desiredOutput;
        boolean needMaterial = mod.getItemStorage().getItemCount(_material) + materialsInSlot < desiredOutput;
        boolean needTemplate = mod.getItemStorage().getItemCount(_template) + templatesInSlot < desiredOutput;

        if (needTool || needMaterial || needTemplate) {
            setDebugState("Getting materials + tools");
            // Only gather what's actually missing
            List<ItemTarget> needed = new ArrayList<>();
            if (needTool) needed.add(_tool);
            if (needMaterial) needed.add(_material);
            if (needTemplate) needed.add(_template);
            return TaskCatalogue.getSquashedItemTask(needed.toArray(new ItemTarget[0]));
        }

        // Edge case: We are wearing the armor we want to upgrade. If so, remove it.
        if (StorageHelper.isArmorEquipped(_tool.getMatches())) {
            // Exit out of any screen so we can move our armor
            if (!(mod.getPlayer().currentScreenHandler instanceof PlayerScreenHandler)) {
                StorageHelper.cleanupCursorSlot(mod);
                setDebugState("Closing screen to remove armor");
                return null;
            }
            // Take off our armor
            if (mod.getItemStorage().hasEmptyInventorySlot()) {
                return new EnsureFreeInventorySlotTask();
            }
            for (Slot armorSlot : PlayerSlot.ARMOR_SLOTS) {
                if (_tool.matches(StorageHelper.getItemStackInSlot(armorSlot).getItem())) {
                    setDebugState("Quickly removing equipped armor");
                    mod.getSlotHandler().clickSlot(armorSlot, 0, SlotActionType.QUICK_MOVE);
                    return null;
                }
            }
        }

        setDebugState("Smithing...");
        return _innerTask;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof UpgradeInSmithingTableTask task) {
            return task._tool.equals(_tool) && task._output.equals(_output) && task._material.equals(_material);
        }
        return false;
    }

    @Override
    protected String toDebugStringName() {
        return "Upgrading " + _tool.toString() + " + " + _material.toString() + " -> " + _output.toString();
    }

    public ItemTarget getTools() {
        return _tool;
    }

    public ItemTarget getMaterials() {
        return _material;
    }

    private class UpgradeInSmithingTableInternalTask extends DoStuffInContainerTask {

        private final TimerGame _invTimer;

        public UpgradeInSmithingTableInternalTask() {
            super(Blocks.SMITHING_TABLE, new ItemTarget("smithing_table"));
            _invTimer = new TimerGame(0);
        }

        @Override
        protected boolean isSubTaskEqual(DoStuffInContainerTask other) {
            // inner part, don't care
            return true;
        }

        @Override
        protected boolean isContainerOpen(AltoClef mod) {
            return (mod.getPlayer().currentScreenHandler instanceof SmithingScreenHandler);
        }

        @Override
        protected Task containerSubTask(AltoClef mod) {
            setDebugState("Smithing...");
            // We have our tools + materials. Now, do the thing.
            _invTimer.setInterval(mod.getModSettings().getContainerItemMoveDelay());

            // Run once every
            if (!_invTimer.elapsed()) {
                return null;
            }
            _invTimer.reset();

            Slot templateSlot = SmithingTableSlot.INPUT_SLOT_TEMPLATE;
            Slot materialSlot = SmithingTableSlot.INPUT_SLOT_MATERIALS;
            Slot toolSlot = SmithingTableSlot.INPUT_SLOT_TOOL;
            Slot outputSlot = SmithingTableSlot.OUTPUT_SLOT;

            ItemStack currentTemplates = StorageHelper.getItemStackInSlot(templateSlot);
            ItemStack currentMaterials = StorageHelper.getItemStackInSlot(materialSlot);
            ItemStack currentTools = StorageHelper.getItemStackInSlot(toolSlot);
            ItemStack currentOutput = StorageHelper.getItemStackInSlot(outputSlot);
            // Grab from output
            if (!currentOutput.isEmpty()) {
                mod.getSlotHandler().clickSlot(outputSlot, 0, SlotActionType.QUICK_MOVE);
                return null;
            }
            // Put materials in slot
            if (currentMaterials.isEmpty() || !_material.matches(currentMaterials.getItem())) {
                return new MoveItemToSlotFromInventoryTask(new ItemTarget(_material, 1), materialSlot);
            }
            // Put tool in slot
            if (currentTools.isEmpty() || !_tool.matches(currentTools.getItem())) {
                return new MoveItemToSlotFromInventoryTask(new ItemTarget(_tool, 1), toolSlot);
            }

            if (currentTemplates.isEmpty() || !_template.matches(currentTemplates.getItem())) {
                return new MoveItemToSlotFromInventoryTask(new ItemTarget(_template, 1), templateSlot);
            }

            setDebugState("PROBLEM: Nothing to do!");
            return null;
        }

        @Override
        protected double getCostToMakeNew(AltoClef mod) {
            int price = 400;
            if (mod.getItemStorage().hasItem(ItemHelper.LOG) || mod.getItemStorage().getItemCount(ItemHelper.PLANKS) >= 4) {
                price -= 125;
            }
            if (mod.getItemStorage().getItemCount(Items.FLINT) >= 2) {
                price -= 125;
            }
            return price;
        }
    }

}
