package com.emma.logger.events;

import com.emma.logger.LogConfig;
import com.emma.logger.PlayerLogger;
import com.google.gson.JsonObject;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

/**
 * Detects hotbar slot changes and logs them with trigger context
 * (nearest hostile, LOS, distance) to explain WHY the player switched.
 */
public class EquipmentChangeLogger {

    private int lastSelectedSlot = -1;
    private String lastHeldItem = "";

    public void tick(ServerPlayer player, long currentTick, PlayerLogger logger) {
        Inventory inv = player.getInventory();
        int selectedSlot = inv.getSelectedSlot();
        ItemStack currentStack = player.getMainHandItem();
        String currentItem = currentStack.isEmpty() ? "empty" :
                BuiltInRegistries.ITEM.getKey(currentStack.getItem()).toString();

        if (selectedSlot != lastSelectedSlot || !currentItem.equals(lastHeldItem)) {
            if (lastSelectedSlot >= 0) { // skip first tick
                JsonObject event = new JsonObject();
                event.addProperty("tick", currentTick);
                event.addProperty("type", "hotbar_select");
                event.addProperty("from", lastHeldItem);
                event.addProperty("to", currentItem);
                event.addProperty("slot", selectedSlot);
                event.addProperty("prev_slot", lastSelectedSlot);

                // Trigger context: nearest hostile with LOS
                event.add("trigger", captureTriggerContext(player));

                logger.logEvent(event);
            }
            lastSelectedSlot = selectedSlot;
            lastHeldItem = currentItem;
        }
    }

    private JsonObject captureTriggerContext(ServerPlayer player) {
        JsonObject ctx = new JsonObject();
        ServerLevel world = (ServerLevel) player.level();

        // Find nearest hostile
        AABB scanBox = player.getBoundingBox().inflate(LogConfig.entityScanRadius);
        Entity nearestHostile = null;
        double nearestDist = Double.MAX_VALUE;

        for (Entity entity : world.getEntities(player, scanBox)) {
            if (entity instanceof Monster hostile) {
                double dist = hostile.distanceTo(player);
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearestHostile = hostile;
                }
            }
        }

        if (nearestHostile != null) {
            ctx.addProperty("nearest_hostile",
                    BuiltInRegistries.ENTITY_TYPE.getKey(nearestHostile.getType()).toString());
            ctx.addProperty("dist", Math.round(nearestDist * 10.0) / 10.0);

            if (nearestHostile instanceof Mob mob) {
                ctx.addProperty("targeting", mob.getTarget() == player);
            }
        }

        ctx.addProperty("health", Math.round(player.getHealth() * 10.0f) / 10.0f);
        ctx.addProperty("hunger", player.getFoodData().getFoodLevel());

        return ctx;
    }
}
