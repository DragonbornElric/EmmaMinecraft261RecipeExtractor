package com.emma.bridge.commands;

import com.emma.bridge.EmmaBridgeMod;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.resources.Identifier;

/**
 * Handles "set_slot" command — changes the player's selected hotbar slot.
 * Can set by slot index (0-8) or by item name (searches full inventory).
 * If item is in main inventory (not hotbar), auto-moves it to a hotbar slot.
 *
 * Params: { "slot": int (0-8) } OR { "item": string (e.g. "diamond_pickaxe") }
 * Returns: { "success": bool, "slot": int, "item": string, "moved_from": int (optional) }
 */
public class SetSlotHandler implements ICommandHandler {

    @Override
    public String getCommand() {
        return "set_slot";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        JsonObject result = new JsonObject();

        if (player == null) {
            result.addProperty("success", false);
            result.addProperty("reason", "no_player");
            return result;
        }

        Inventory inv = player.getInventory();
        int targetSlot = -1;

        if (params.has("slot")) {
            targetSlot = params.get("slot").getAsInt();
            if (targetSlot < 0 || targetSlot > 8) {
                result.addProperty("success", false);
                result.addProperty("reason", "slot_out_of_range");
                return result;
            }
        } else if (params.has("item")) {
            String itemName = params.get("item").getAsString().toLowerCase();
            // Add minecraft: prefix if not present
            if (!itemName.contains(":")) {
                itemName = "minecraft:" + itemName;
            }

            // Search hotbar first (slots 0-8)
            for (int i = 0; i < 9; i++) {
                ItemStack stack = inv.getItem(i);
                if (!stack.isEmpty()) {
                    String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                    if (id.equals(itemName)) {
                        targetSlot = i;
                        break;
                    }
                }
            }

            // Not in hotbar — search main inventory (9-35), armor (36-39), offhand (40)
            if (targetSlot == -1) {
                int sourceSlot = -1;
                for (int i = 9; i <= 40; i++) {
                    ItemStack stack = inv.getItem(i);
                    if (!stack.isEmpty()) {
                        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                        if (id.equals(itemName)) {
                            sourceSlot = i;
                            break;
                        }
                    }
                }

                if (sourceSlot == -1) {
                    result.addProperty("success", false);
                    result.addProperty("reason", "item_not_found");
                    result.addProperty("searched_for", itemName);
                    return result;
                }

                // Find an empty hotbar slot, fall back to currently selected
                int destHotbar = inv.getSelectedSlot();
                for (int i = 0; i < 9; i++) {
                    if (inv.getItem(i).isEmpty()) {
                        destHotbar = i;
                        break;
                    }
                }

                // Convert Inventory index to InventoryMenu index for SWAP
                // InventoryMenu (default, no GUI open):
                //   0=craft output, 1-4=craft grid, 5=head, 6=chest, 7=legs, 8=feet
                //   9-35=main inventory, 36-44=hotbar, 45=offhand
                int screenSlot;
                if (sourceSlot >= 9 && sourceSlot <= 35) {
                    screenSlot = sourceSlot;  // Same mapping
                } else if (sourceSlot == 40) {
                    screenSlot = 45;  // Offhand
                } else {
                    // Armor: Inventory 36=feet→8, 37=legs→7, 38=chest→6, 39=head→5
                    screenSlot = 8 - (sourceSlot - 36);
                }

                // SWAP: moves screen slot content into hotbar slot
                int syncId = player.containerMenu.containerId;
                client.gameMode.handleContainerInput(
                        syncId, screenSlot, destHotbar, ContainerInput.SWAP, player);

                targetSlot = destHotbar;
                result.addProperty("moved_from", sourceSlot);
            }
        } else {
            result.addProperty("success", false);
            result.addProperty("reason", "provide_slot_or_item");
            return result;
        }

        inv.setSelectedSlot(targetSlot);
        ItemStack selected = inv.getItem(targetSlot);
        String itemName = selected.isEmpty() ? "empty"
                : BuiltInRegistries.ITEM.getKey(selected.getItem()).toString();

        result.addProperty("success", true);
        result.addProperty("slot", targetSlot);
        result.addProperty("item", itemName);

        EmmaBridgeMod.LOGGER.info("[Emma Bridge] set_slot: slot={} item={}", targetSlot, itemName);
        return result;
    }
}
