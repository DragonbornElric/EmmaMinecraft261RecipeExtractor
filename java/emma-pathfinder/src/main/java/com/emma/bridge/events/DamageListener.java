package com.emma.bridge.events;

import com.emma.bridge.mixin.DeathScreenAccessor;
import com.emma.bridge.BridgeServer;
import com.emma.bridge.goap.DeathContext;
import com.emma.bridge.websocket.JsonProtocol;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;

/**
 * Tracks damage taken by the player. Uses a tick-based approach to detect
 * health decreases and report them with context.
 *
 * Also detects when the DeathScreen appears and sends the death message
 * text (e.g., "Emma was slain by Zombie") as a follow-up event.
 */
public class DamageListener {

    private float lastKnownHealth = -1;
    private long lastDamageTime = 0;
    private boolean deathScreenSent = false;

    /** Minimum interval between damage reports (ms) to prevent spam. */
    private static final long DAMAGE_COOLDOWN_MS = 100;

    /**
     * Called every tick by EventReporter. Detects health drops and reports them.
     * Also watches for the DeathScreen to capture the death message text.
     */
    public void tick(LocalPlayer player, BridgeServer ws) {
        float currentHealth = player.getHealth();

        if (lastKnownHealth < 0) {
            // First tick — initialize
            lastKnownHealth = currentHealth;
            return;
        }

        // Detect DeathScreen appearing (may be 1+ ticks after health=0)
        Minecraft client = Minecraft.getInstance();
        Screen currentScreen = client.screen;
        if (currentScreen instanceof DeathScreen deathScreen && !deathScreenSent) {
            deathScreenSent = true;
            Component causeText = ((DeathScreenAccessor) deathScreen).getMessage();
            String deathMessage = causeText != null ? causeText.getString() : "Unknown cause";

            // Emit legacy death_message event (existing behavior)
            if (deathMessage != null && !deathMessage.isEmpty()) {
                JsonObject data = new JsonObject();
                data.addProperty("death_message", deathMessage);
                ws.broadcastEvent(JsonProtocol.event("death_message", data));
            }

            // Build and emit full death post-mortem
            JsonObject postMortem = buildPostMortem(player, client.level, deathMessage);
            ws.broadcastEvent(JsonProtocol.event("death_postmortem", postMortem));

            // Populate DeathContext for GOAP death recovery action
            int hostileCount = postMortem.has("hostile_count")
                    ? postMortem.get("hostile_count").getAsInt() : 0;
            DeathContext.getInstance().recordDeath(
                    player.getX(), player.getY(), player.getZ(),
                    player.level().dimension().identifier().toString(),
                    deathMessage, hostileCount);
        } else if (!(currentScreen instanceof DeathScreen)) {
            // Reset flag when DeathScreen closes (respawn)
            deathScreenSent = false;
        }

        if (currentHealth < lastKnownHealth) {
            float amount = lastKnownHealth - currentHealth;

            // Always record to CombatLog (no cooldown — ring buffer handles volume)
            JsonObject combatData = new JsonObject();
            combatData.addProperty("amount", amount);
            combatData.addProperty("health_after", currentHealth);
            combatData.addProperty("max_health", player.getMaxHealth());
            if (player.getLastHurtByMob() != null) {
                combatData.addProperty("attacker_type",
                        player.getLastHurtByMob().getType().getDescriptionId());
                combatData.addProperty("attacker_distance",
                        player.distanceTo(player.getLastHurtByMob()));
            }
            if (currentHealth <= 0) {
                combatData.addProperty("fatal", true);
            }
            CombatLog.getInstance().record("damage_taken", combatData);

            // Throttled WebSocket broadcast (existing behavior)
            long now = System.currentTimeMillis();
            if (now - lastDamageTime > DAMAGE_COOLDOWN_MS) {
                JsonObject data = new JsonObject();
                data.addProperty("amount", amount);
                data.addProperty("health_remaining", currentHealth);
                data.addProperty("max_health", player.getMaxHealth());

                if (player.getLastHurtByMob() != null) {
                    data.addProperty("attacker_type",
                            player.getLastHurtByMob().getType().getDescriptionId());
                    data.addProperty("attacker_distance",
                            player.distanceTo(player.getLastHurtByMob()));
                }

                if (currentHealth <= 0) {
                    data.addProperty("fatal", true);
                }

                ws.broadcastEvent(JsonProtocol.event("damage_taken", data));
                lastDamageTime = now;
            }
        } else if (currentHealth > lastKnownHealth && lastKnownHealth >= 0) {
            // Health increased — record heal event to CombatLog
            float healAmount = currentHealth - lastKnownHealth;
            JsonObject healData = new JsonObject();
            healData.addProperty("amount", healAmount);
            healData.addProperty("health_after", currentHealth);
            CombatLog.getInstance().record("heal", healData);
        }

        lastKnownHealth = currentHealth;
    }

    /**
     * Reset state (e.g., on respawn or dimension change).
     */
    public void reset() {
        lastKnownHealth = -1;
        deathScreenSent = false;
    }

    /**
     * Build a comprehensive death post-mortem payload.
     * Contains everything needed to reconstruct why Emma died.
     */
    private JsonObject buildPostMortem(LocalPlayer player, ClientLevel world, String deathMessage) {
        JsonObject pm = new JsonObject();
        pm.addProperty("timestamp_ms", System.currentTimeMillis());
        pm.addProperty("death_message", deathMessage != null ? deathMessage : "Unknown cause");

        // Player state
        JsonObject playerState = new JsonObject();
        playerState.addProperty("x", player.getX());
        playerState.addProperty("y", player.getY());
        playerState.addProperty("z", player.getZ());
        playerState.addProperty("dimension", player.level().dimension().identifier().toString());
        playerState.addProperty("armor_value", player.getArmorValue());
        playerState.addProperty("food_level", player.getFoodData().getFoodLevel());
        playerState.addProperty("was_on_fire", player.isOnFire());
        playerState.addProperty("was_in_water", player.isInWater());
        pm.add("player_state", playerState);

        // Attacker info
        Entity attacker = player.getLastHurtByMob();
        if (attacker != null) {
            JsonObject attackerInfo = new JsonObject();
            attackerInfo.addProperty("type", attacker.getType().getDescriptionId());
            attackerInfo.addProperty("distance", player.distanceTo(attacker));
            attackerInfo.addProperty("x", attacker.getX());
            attackerInfo.addProperty("y", attacker.getY());
            attackerInfo.addProperty("z", attacker.getZ());
            if (attacker instanceof LivingEntity living) {
                attackerInfo.addProperty("health", living.getHealth());
                attackerInfo.addProperty("max_health", living.getMaxHealth());
            }
            pm.add("attacker_info", attackerInfo);
        }

        // Nearby hostiles (up to 8, within 16 blocks)
        if (world != null) {
            JsonArray hostiles = new JsonArray();
            AABB scanBox = player.getBoundingBox().inflate(16);
            int count = 0;
            for (Entity entity : world.getEntities(player, scanBox)) {
                if (entity instanceof Monster hostile && count < 8) {
                    JsonObject h = new JsonObject();
                    h.addProperty("type", hostile.getType().getDescriptionId());
                    h.addProperty("health", hostile.getHealth());
                    h.addProperty("distance", player.distanceTo(hostile));
                    hostiles.add(h);
                    count++;
                }
            }
            pm.add("nearby_hostiles", hostiles);
            pm.addProperty("hostile_count", count);
        }

        // Full combat log dump
        pm.add("combat_log", CombatLog.getInstance().snapshot());

        return pm;
    }
}
