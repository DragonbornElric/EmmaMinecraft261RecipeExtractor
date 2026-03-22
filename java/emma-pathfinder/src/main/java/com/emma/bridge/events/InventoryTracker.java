package com.emma.bridge.events;

import com.emma.bridge.BridgeConfig;
import com.emma.bridge.BridgeServer;
import com.emma.bridge.websocket.JsonProtocol;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * Deduplicated inventory change tracker. Only fires when inventory hash
 * changes, with a configurable debounce to batch rapid changes.
 */
public class InventoryTracker {

    private String lastInventoryHash = "";
    private long lastChangeTime = 0;
    private boolean pendingReport = false;

    public void tick(LocalPlayer player, BridgeServer ws) {
        String currentHash = computeInventoryHash(player.getInventory());

        if (!currentHash.equals(lastInventoryHash)) {
            lastInventoryHash = currentHash;
            lastChangeTime = System.currentTimeMillis();
            pendingReport = true;
        }

        // Debounce: wait for inventory to stabilize
        if (pendingReport &&
                System.currentTimeMillis() - lastChangeTime > BridgeConfig.getInventoryDebounceMs()) {

            pendingReport = false;
            JsonObject data = serializeInventory(player.getInventory());
            ws.broadcastEvent(JsonProtocol.event("inventory_changed", data));
        }
    }

    private String computeInventoryHash(Inventory inv) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                sb.append(i).append(":")
                        .append(BuiltInRegistries.ITEM.getKey(stack.getItem()))
                        .append("x").append(stack.getCount()).append(";");
            }
        }
        return sb.toString();
    }

    private JsonObject serializeInventory(Inventory inv) {
        JsonArray slots = new JsonArray();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                JsonObject slot = new JsonObject();
                slot.addProperty("slot", i);
                slot.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                slot.addProperty("count", stack.getCount());
                slots.add(slot);
            }
        }
        JsonObject data = new JsonObject();
        data.add("slots", slots);
        data.addProperty("selected_slot", getSelectedSlot(inv));
        return data;
    }

    private int getSelectedSlot(Inventory inv) {
        try {
            java.lang.reflect.Field f = Inventory.class.getDeclaredField("selectedSlot");
            f.setAccessible(true);
            return f.getInt(inv);
        } catch (Exception e) {
            return 0;
        }
    }
}
