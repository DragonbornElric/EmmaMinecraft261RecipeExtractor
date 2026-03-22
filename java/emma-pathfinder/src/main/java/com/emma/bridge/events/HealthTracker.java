package com.emma.bridge.events;

import com.emma.bridge.BridgeServer;
import com.emma.bridge.websocket.JsonProtocol;
import com.google.gson.JsonObject;
import net.minecraft.client.player.LocalPlayer;

/**
 * Tracks health, hunger, and armor changes. Only emits health_changed
 * when values actually differ from the last report (deduplication).
 */
public class HealthTracker {

    private float lastHealth = -1;
    private int lastHunger = -1;
    private int lastArmor = -1;

    public void tick(LocalPlayer player, BridgeServer ws) {
        float health = player.getHealth();
        int hunger = player.getFoodData().getFoodLevel();
        int armor = player.getArmorValue();

        if (health == lastHealth && hunger == lastHunger && armor == lastArmor) {
            return; // no change
        }

        // Record armor changes to CombatLog
        if (armor != lastArmor && lastArmor >= 0) {
            JsonObject armorData = new JsonObject();
            armorData.addProperty("old_value", lastArmor);
            armorData.addProperty("new_value", armor);
            CombatLog.getInstance().record("armor_changed", armorData);
        }

        // Record food eaten to CombatLog (hunger increased = food consumed)
        if (hunger > lastHunger && lastHunger >= 0) {
            JsonObject foodData = new JsonObject();
            foodData.addProperty("hunger_after", hunger);
            CombatLog.getInstance().record("food_eaten", foodData);
        }

        lastHealth = health;
        lastHunger = hunger;
        lastArmor = armor;

        JsonObject data = new JsonObject();
        data.addProperty("health", health);
        data.addProperty("max_health", player.getMaxHealth());
        data.addProperty("hunger", hunger);
        data.addProperty("armor", armor);
        data.addProperty("absorption", player.getAbsorptionAmount());

        ws.broadcastEvent(JsonProtocol.event("health_changed", data));
    }
}
