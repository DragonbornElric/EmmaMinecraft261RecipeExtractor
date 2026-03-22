package com.emma.bridge.commands;

import com.emma.bridge.EmmaBridgeMod;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * Handles "get_effects" command — returns the player's active status effects.
 *
 * Params: {} (none)
 * Returns: { "effects": [{"id": string, "duration": int, "amplifier": int}], "count": int }
 */
public class GetEffectsHandler implements ICommandHandler {

    @Override
    public String getCommand() {
        return "get_effects";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        LocalPlayer player = Minecraft.getInstance().player;
        JsonObject result = new JsonObject();

        if (player == null) {
            result.addProperty("count", 0);
            result.addProperty("reason", "no_player");
            return result;
        }

        JsonArray effects = new JsonArray();
        for (var entry : player.getActiveEffectsMap().entrySet()) {
            MobEffectInstance effect = entry.getValue();
            JsonObject effectObj = new JsonObject();
            effectObj.addProperty("id", BuiltInRegistries.MOB_EFFECT.getKey(entry.getKey().value()).toString());
            effectObj.addProperty("duration", effect.getDuration());
            effectObj.addProperty("amplifier", effect.getAmplifier());
            effectObj.addProperty("ambient", effect.isAmbient());
            effects.add(effectObj);
        }

        result.add("effects", effects);
        result.addProperty("count", effects.size());

        EmmaBridgeMod.LOGGER.debug("[Emma Bridge] get_effects: {} active", effects.size());
        return result;
    }
}
