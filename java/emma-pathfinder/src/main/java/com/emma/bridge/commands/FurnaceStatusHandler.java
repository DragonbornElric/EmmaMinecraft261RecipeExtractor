package com.emma.bridge.commands;

import com.emma.bridge.mixin.AbstractFurnaceScreenHandlerAccessor;
import com.emma.bridge.EmmaBridgeMod;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

/**
 * Handles "furnace_status" command — reads the currently open furnace screen's
 * contents and cooking progress via ContainerData.
 *
 * The furnace screen must already be open (use interact_block first, then call
 * this command).  Returns slot contents plus burn/cook timer data that the
 * generic read_screen command doesn't expose.
 *
 * Params:  {} (none — reads the currently open screen)
 * Returns: { "furnace_type": string, "input_item": string, "input_count": int,
 *            "fuel_item": string, "fuel_count": int, "output_item": string,
 *            "output_count": int, "cook_progress": float (0-1),
 *            "burn_time_remaining": int, "is_burning": bool }
 */
public class FurnaceStatusHandler implements ICommandHandler {

    @Override
    public String getCommand() {
        return "furnace_status";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        JsonObject result = new JsonObject();

        if (player == null) {
            result.addProperty("error", "no_player");
            return result;
        }

        AbstractContainerMenu handler = player.containerMenu;
        if (handler == null) {
            result.addProperty("error", "no_screen_open");
            return result;
        }

        // Verify it's a furnace-type screen handler
        if (!(handler instanceof AbstractFurnaceMenu)) {
            result.addProperty("error", "not_a_furnace_screen");
            result.addProperty("screen_type", handler.getClass().getSimpleName());
            return result;
        }

        // Determine furnace type from handler class name
        String handlerName = handler.getClass().getSimpleName().toLowerCase();
        String furnaceType = "furnace";
        if (handlerName.contains("blast")) furnaceType = "blast_furnace";
        else if (handlerName.contains("smoker")) furnaceType = "smoker";
        result.addProperty("furnace_type", furnaceType);

        // Read slots: 0=input, 1=fuel, 2=output
        addSlotInfo(result, handler, 0, "input");
        addSlotInfo(result, handler, 1, "fuel");
        addSlotInfo(result, handler, 2, "output");

        // Read ContainerData via mixin accessor (field is private):
        // [0]=burn time, [1]=total burn time, [2]=cook time, [3]=total cook time
        try {
            ContainerData pd = ((AbstractFurnaceScreenHandlerAccessor) handler).getPropertyDelegate();
            int burnTime = pd.get(0);
            int totalBurnTime = pd.get(1);
            int cookTime = pd.get(2);
            int totalCookTime = pd.get(3);

            result.addProperty("is_burning", burnTime > 0);
            result.addProperty("burn_time_remaining", burnTime);
            result.addProperty("total_burn_time", totalBurnTime);
            float cookProgress = totalCookTime > 0 ? (float) cookTime / totalCookTime : 0f;
            result.addProperty("cook_progress", cookProgress);
            result.addProperty("cook_time", cookTime);
            result.addProperty("total_cook_time", totalCookTime);
        } catch (Exception e) {
            result.addProperty("cook_progress", 0);
            result.addProperty("is_burning", false);
            EmmaBridgeMod.LOGGER.warn("[Emma Bridge] furnace_status: ContainerData read failed: {}",
                    e.getMessage());
        }

        EmmaBridgeMod.LOGGER.info("[Emma Bridge] furnace_status: type={}", furnaceType);
        return result;
    }

    private void addSlotInfo(JsonObject result, AbstractContainerMenu handler, int slotIndex, String prefix) {
        if (slotIndex < handler.slots.size()) {
            Slot slot = handler.slots.get(slotIndex);
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty()) {
                result.addProperty(prefix + "_item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                result.addProperty(prefix + "_count", stack.getCount());
            } else {
                result.addProperty(prefix + "_item", "empty");
                result.addProperty(prefix + "_count", 0);
            }
        }
    }
}
