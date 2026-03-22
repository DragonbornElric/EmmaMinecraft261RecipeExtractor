package com.emma.logger.state;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;

/**
 * Captures full inventory state: hotbar, armor, offhand, main inventory.
 */
public class EquipmentStateCapture {

    public JsonObject capture(ServerPlayer player) {
        JsonObject obj = new JsonObject();
        Inventory inv = player.getInventory();

        obj.addProperty("selected_slot", inv.getSelectedSlot());

        // Hotbar (slots 0-8)
        JsonArray hotbar = new JsonArray();
        for (int i = 0; i < 9; i++) {
            hotbar.add(serializeSlot(inv.getItem(i), i));
        }
        obj.add("hotbar", hotbar);

        // Armor
        JsonArray armor = new JsonArray();
        String[] armorSlotNames = {"feet", "legs", "chest", "head"};
        EquipmentSlot[] armorSlots = {EquipmentSlot.FEET, EquipmentSlot.LEGS,
                EquipmentSlot.CHEST, EquipmentSlot.HEAD};
        for (int i = 0; i < 4; i++) {
            ItemStack stack = player.getItemBySlot(armorSlots[i]);
            if (!stack.isEmpty()) {
                JsonObject slot = serializeItemDetail(stack);
                slot.addProperty("slot", armorSlotNames[i]);
                armor.add(slot);
            }
        }
        obj.add("armor", armor);

        // Offhand
        ItemStack offhand = player.getOffhandItem();
        if (!offhand.isEmpty()) {
            obj.add("offhand", serializeItemDetail(offhand));
        }

        // Main inventory (slots 9-35)
        JsonArray mainInv = new JsonArray();
        for (int i = 9; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                JsonObject slot = new JsonObject();
                slot.addProperty("slot", i);
                slot.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                slot.addProperty("count", stack.getCount());
                mainInv.add(slot);
            }
        }
        obj.add("inventory", mainInv);

        // Summary stats
        int freeSlots = 0;
        int totalItems = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                freeSlots++;
            } else {
                totalItems += stack.getCount();
            }
        }
        obj.addProperty("free_slots", freeSlots);
        obj.addProperty("total_item_count", totalItems);

        return obj;
    }

    private JsonObject serializeSlot(ItemStack stack, int slotIndex) {
        JsonObject slot = new JsonObject();
        slot.addProperty("slot", slotIndex);
        if (stack.isEmpty()) {
            slot.add("item", JsonNull.INSTANCE);
            return slot;
        }
        slot.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        slot.addProperty("count", stack.getCount());

        // Durability if applicable
        if (stack.isDamageableItem()) {
            slot.addProperty("durability", stack.getMaxDamage() - stack.getDamageValue());
            slot.addProperty("max_durability", stack.getMaxDamage());
        }

        return slot;
    }

    private JsonObject serializeItemDetail(ItemStack stack) {
        JsonObject obj = new JsonObject();
        obj.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        obj.addProperty("count", stack.getCount());

        if (stack.isDamageableItem()) {
            obj.addProperty("durability", stack.getMaxDamage() - stack.getDamageValue());
            obj.addProperty("max_durability", stack.getMaxDamage());
        }

        // Enchantments
        ItemEnchantments enchants = stack.get(DataComponents.ENCHANTMENTS);
        if (enchants != null && !enchants.isEmpty()) {
            JsonArray enchArray = new JsonArray();
            for (Holder<Enchantment> entry : enchants.keySet()) {
                JsonObject ench = new JsonObject();
                entry.unwrapKey().ifPresent(key ->
                        ench.addProperty("id", key.identifier().toString()));
                ench.addProperty("level", enchants.getLevel(entry));
                enchArray.add(ench);
            }
            obj.add("enchantments", enchArray);
        }

        return obj;
    }
}
