package com.emma.logger.state;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.biome.Biome;

import java.util.Optional;

/**
 * Captures player position, vitals, movement state, and environment info.
 */
public class PlayerStateCapture {

    public JsonObject capture(ServerPlayer player) {
        JsonObject obj = new JsonObject();
        Vec3 pos = player.position();
        Vec3 vel = player.getDeltaMovement();

        // Position + rotation
        obj.addProperty("x", round(pos.x));
        obj.addProperty("y", round(pos.y));
        obj.addProperty("z", round(pos.z));
        obj.addProperty("yaw", round(player.getYRot()));
        obj.addProperty("pitch", round(player.getXRot()));

        // Velocity
        obj.addProperty("vx", round(vel.x));
        obj.addProperty("vy", round(vel.y));
        obj.addProperty("vz", round(vel.z));

        // Movement state
        obj.addProperty("on_ground", player.onGround());
        obj.addProperty("is_sprinting", player.isSprinting());
        obj.addProperty("is_sneaking", player.isShiftKeyDown());
        obj.addProperty("is_swimming", player.isSwimming());

        // Vitals
        obj.addProperty("health", round(player.getHealth()));
        obj.addProperty("hunger", player.getFoodData().getFoodLevel());
        obj.addProperty("saturation", round(player.getFoodData().getSaturationLevel()));
        obj.addProperty("xp_level", player.experienceLevel);
        obj.addProperty("xp_total", player.totalExperience);

        // Status effects
        JsonArray effects = new JsonArray();
        for (MobEffectInstance effect : player.getActiveEffects()) {
            JsonObject eff = new JsonObject();
            eff.addProperty("id", BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value()).toString());
            eff.addProperty("duration", effect.getDuration());
            eff.addProperty("amplifier", effect.getAmplifier());
            effects.add(eff);
        }
        if (!effects.isEmpty()) {
            obj.add("active_effects", effects);
        }

        // Environment
        ServerLevel world = (ServerLevel) player.level();
        obj.addProperty("dimension", world.dimension().identifier().toString());

        // Biome
        BlockPos blockPos = player.blockPosition();
        Optional<ResourceKey<Biome>> biomeKey = world.getBiome(blockPos).unwrapKey();
        biomeKey.ifPresent(key -> obj.addProperty("biome", key.identifier().toString()));

        obj.addProperty("light_level", world.getMaxLocalRawBrightness(blockPos));
        obj.addProperty("is_on_fire", player.isOnFire());
        obj.addProperty("air_supply", player.getAirSupply());

        return obj;
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private float round(float v) {
        return Math.round(v * 100.0f) / 100.0f;
    }
}
