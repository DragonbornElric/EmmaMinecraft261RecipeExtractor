package com.emma.bridge.commands;

import com.emma.bridge.EmmaBridgeMod;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.EntityHitResult;
/**
 * Handles "interact_entity" command — right-clicks the nearest entity matching a filter.
 * Covers: villager trading, mounting horses/boats/minecarts, shearing sheep,
 * leashing animals, naming with nametags, feeding animals, etc.
 *
 * Params: { "type": string (optional entity type filter, e.g. "villager"),
 *           "radius": int (optional, default 5),
 *           "hand": "main"|"off" (default "main") }
 * Returns: { "interacted": bool, "entity_type": string, "x": float, "y": float, "z": float }
 */
public class InteractEntityHandler implements ICommandHandler {

    @Override
    public String getCommand() {
        return "interact_entity";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        LocalPlayer player = HandlerUtils.requirePlayer();
        JsonObject result = new JsonObject();

        if (player == null) {
            result.addProperty("interacted", false);
            result.addProperty("reason", "no_player");
            return result;
        }
        Minecraft client = Minecraft.getInstance();

        int radius = params.has("radius") ? params.get("radius").getAsInt() : 5;
        String typeFilter = params.has("type") ? params.get("type").getAsString().toLowerCase() : null;
        InteractionHand hand = HandlerUtils.parseHand(params);

        Entity nearest = HandlerUtils.findNearestEntity(player, radius, entity -> {
            if (!entity.isAlive()) return false;
            if (typeFilter == null) return true;
            String entityType = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
            String shortType = entityType.replace("minecraft:", "");
            return shortType.contains(typeFilter) || entityType.contains(typeFilter);
        });

        if (nearest == null) {
            result.addProperty("interacted", false);
            result.addProperty("reason", typeFilter != null ? "no_matching_entity" : "no_entity_nearby");
            return result;
        }

        String entityType = BuiltInRegistries.ENTITY_TYPE.getKey(nearest.getType()).toString();
        double nearestDist = player.distanceTo(nearest);

        if (nearestDist > 6.0) {
            result.addProperty("interacted", false);
            result.addProperty("reason", "out_of_range");
            result.addProperty("entity_type", entityType);
            result.addProperty("distance", nearestDist);
            return result;
        }

        // Right-click the entity
        client.gameMode.interact(player, nearest, new EntityHitResult(nearest), hand);

        result.addProperty("interacted", true);
        result.addProperty("entity_type", entityType);
        result.addProperty("distance", nearestDist);
        result.addProperty("x", Math.round(nearest.getX() * 10.0) / 10.0);
        result.addProperty("y", Math.round(nearest.getY() * 10.0) / 10.0);
        result.addProperty("z", Math.round(nearest.getZ() * 10.0) / 10.0);

        if (nearest instanceof LivingEntity living) {
            result.addProperty("health", living.getHealth());
            result.addProperty("max_health", living.getMaxHealth());
        }

        EmmaBridgeMod.LOGGER.info("[Emma Bridge] interact_entity: {} at distance {:.1f}",
                entityType, nearestDist);
        return result;
    }
}
