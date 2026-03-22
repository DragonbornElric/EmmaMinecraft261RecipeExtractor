package com.emma.bridge.events;

import com.emma.bridge.BridgeConfig;
import com.emma.bridge.BridgeServer;
import com.emma.bridge.websocket.JsonProtocol;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Periodic nearby entity scanner. Scans entities within a configurable
 * radius at a configurable interval and reports them.
 */
public class EntityScanner {

    private long lastScanTime = 0;

    public void tick(LocalPlayer player, Level world, BridgeServer ws) {
        long now = System.currentTimeMillis();
        if (now - lastScanTime < BridgeConfig.getEntityScanIntervalMs()) return;
        lastScanTime = now;

        int radius = BridgeConfig.getEntityScanRadius();
        AABB scanBox = player.getBoundingBox().inflate(radius);
        List<Entity> entities = world.getEntities(player, scanBox);

        JsonArray entitiesArray = new JsonArray();
        for (Entity entity : entities) {
            JsonObject e = new JsonObject();
            e.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
            e.addProperty("x", entity.getX());
            e.addProperty("y", entity.getY());
            e.addProperty("z", entity.getZ());
            e.addProperty("distance", player.distanceTo(entity));

            // Classify entity
            if (entity instanceof Monster) {
                e.addProperty("category", "hostile");
            } else if (entity instanceof Animal) {
                e.addProperty("category", "animal");
            } else if (entity instanceof Player) {
                e.addProperty("category", "player");
                e.addProperty("name", entity.getName().getString());
            } else {
                e.addProperty("category", "other");
            }

            // Health if living
            if (entity instanceof LivingEntity living) {
                e.addProperty("health", living.getHealth());
                e.addProperty("max_health", living.getMaxHealth());
            }

            entitiesArray.add(e);
        }

        JsonObject data = new JsonObject();
        data.add("entities", entitiesArray);
        data.addProperty("count", entitiesArray.size());
        data.addProperty("scan_radius", radius);

        // Include player air level when submerged (for drowning detection)
        int air = player.getAirSupply();
        int maxAir = player.getMaxAirSupply();
        if (air < maxAir) {
            data.addProperty("air", air);
            data.addProperty("max_air", maxAir);
        }

        ws.broadcastEvent(JsonProtocol.event("nearby_entities", data));
    }
}
