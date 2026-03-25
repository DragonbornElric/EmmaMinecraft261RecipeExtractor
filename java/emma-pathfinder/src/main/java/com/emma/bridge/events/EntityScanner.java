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

    /** Combat state tracking for engage/disengage events. */
    private int previousHostileCount = 0;
    private long combatStartMs = 0;
    private long lastCombatEngageMs = 0;
    private static final long COMBAT_ENGAGE_COOLDOWN_MS = 5000;

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

        // Combat engage/disengage detection based on hostile count transitions
        int currentHostileCount = 0;
        JsonArray hostileDetails = new JsonArray();
        for (Entity entity : entities) {
            if (entity instanceof Monster) {
                currentHostileCount++;
                if (hostileDetails.size() < 5) {
                    JsonObject h = new JsonObject();
                    h.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
                    h.addProperty("distance", Math.round(player.distanceTo(entity) * 10.0) / 10.0);
                    if (entity instanceof LivingEntity living) {
                        h.addProperty("health", living.getHealth());
                    }
                    hostileDetails.add(h);
                }
            }
        }

        long now2 = System.currentTimeMillis();

        if (previousHostileCount == 0 && currentHostileCount > 0) {
            // Entered combat — emit combat_engage (throttled)
            if (now2 - lastCombatEngageMs >= COMBAT_ENGAGE_COOLDOWN_MS) {
                combatStartMs = now2;
                lastCombatEngageMs = now2;

                JsonObject engageData = new JsonObject();
                engageData.add("hostiles", hostileDetails);
                engageData.addProperty("total", currentHostileCount);
                ws.broadcastEvent(JsonProtocol.event("combat_engage", engageData));
            }
        } else if (previousHostileCount > 0 && currentHostileCount == 0) {
            // Left combat — emit combat_disengage
            long durationMs = combatStartMs > 0 ? now2 - combatStartMs : 0;

            JsonObject disengageData = new JsonObject();
            disengageData.addProperty("duration_seconds", Math.round(durationMs / 100.0) / 10.0);
            ws.broadcastEvent(JsonProtocol.event("combat_disengage", disengageData));
        }

        previousHostileCount = currentHostileCount;
    }
}
