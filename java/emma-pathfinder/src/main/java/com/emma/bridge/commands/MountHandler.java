package com.emma.bridge.commands;

import com.emma.bridge.EmmaBridgeMod;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.monster.Strider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.EntityHitResult;

/**
 * Handles "mount" and "dismount" commands.
 *
 * mount: Finds and mounts the nearest rideable entity (horse, boat, minecart, pig, strider).
 *   Params: { "type": string (optional filter) }
 *   Returns: { "mounted": bool, "entity_type": string }
 *
 * dismount: Dismounts from the current vehicle.
 *   Params: {} (none)
 *   Returns: { "dismounted": bool }
 */
public class MountHandler implements ICommandHandler {

    @Override
    public String getCommand() {
        return "mount";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        LocalPlayer player = HandlerUtils.requirePlayer();
        JsonObject result = new JsonObject();

        if (player == null) {
            result.addProperty("success", false);
            result.addProperty("reason", "no_player");
            return result;
        }

        // Check if this is a dismount request
        boolean dismount = params.has("dismount") && params.get("dismount").getAsBoolean();

        if (dismount) {
            if (!player.isPassenger()) {
                result.addProperty("dismounted", false);
                result.addProperty("reason", "not_mounted");
                return result;
            }
            player.stopRiding();
            result.addProperty("dismounted", true);
            EmmaBridgeMod.LOGGER.info("[Emma Bridge] dismounted");
            return result;
        }

        // Mount: find nearest rideable entity
        if (player.isPassenger()) {
            String vehicleType = BuiltInRegistries.ENTITY_TYPE.getKey(player.getVehicle().getType()).toString();
            result.addProperty("mounted", false);
            result.addProperty("reason", "already_mounted");
            result.addProperty("vehicle", vehicleType);
            return result;
        }

        String typeFilter = params.has("type") ? params.get("type").getAsString().toLowerCase() : null;
        int radius = params.has("radius") ? params.get("radius").getAsInt() : 5;

        Entity nearest = HandlerUtils.findNearestEntity(player, radius, entity -> {
            if (!entity.isAlive()) return false;
            if (!isRideable(entity)) return false;
            if (typeFilter == null) return true;
            String entityType = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
            String shortType = entityType.replace("minecraft:", "");
            return shortType.contains(typeFilter);
        });

        if (nearest == null) {
            result.addProperty("mounted", false);
            result.addProperty("reason", "no_rideable_entity_nearby");
            return result;
        }

        String entityType = BuiltInRegistries.ENTITY_TYPE.getKey(nearest.getType()).toString();
        double nearestDist = player.distanceTo(nearest);

        // Right-click to mount
        Minecraft.getInstance().gameMode.interact(player, nearest, new EntityHitResult(nearest), InteractionHand.MAIN_HAND);

        result.addProperty("mounted", true);
        result.addProperty("entity_type", entityType);
        result.addProperty("distance", nearestDist);

        EmmaBridgeMod.LOGGER.info("[Emma Bridge] mount: {} at distance {:.1f}",
                entityType, nearestDist);
        return result;
    }

    private boolean isRideable(Entity entity) {
        return entity instanceof AbstractHorse
                || entity instanceof AbstractBoat
                || entity instanceof AbstractMinecart
                || entity instanceof Pig
                || entity instanceof Strider;
    }
}
