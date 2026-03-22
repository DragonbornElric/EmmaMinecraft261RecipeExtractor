package com.emma.bridge.commands;

import emmatone.api.EmmatoneAPI;
import com.emma.bridge.EmmaBridgeMod;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Handles "status" command — returns current game state and Emmatone task info.
 * Available in BOTH player and camera modes.
 *
 * Params: {} (none)
 * Returns: { mod_loaded, in_game, position, health, emmatone_status, ... }
 */
public class StatusHandler implements ICommandHandler {

    @Override
    public String getCommand() {
        return "status";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;

        JsonObject result = new JsonObject();
        result.addProperty("mod_loaded", true);
        result.addProperty("bridge_version",
                FabricLoader.getInstance().getModContainer(EmmaBridgeMod.MOD_ID)
                        .map(c -> c.getMetadata().getVersion().getFriendlyString())
                        .orElse("unknown"));
        result.addProperty("in_game", player != null && client.level != null);

        // Active task info from TaskRegistry
        result.add("active_task", TaskRegistry.toJson());

        if (player != null) {
            // Position
            JsonObject pos = new JsonObject();
            pos.addProperty("x", player.getX());
            pos.addProperty("y", player.getY());
            pos.addProperty("z", player.getZ());
            pos.addProperty("yaw", player.getYRot());
            pos.addProperty("pitch", player.getXRot());
            result.add("position", pos);

            // Health & hunger
            result.addProperty("health", player.getHealth());
            result.addProperty("max_health", player.getMaxHealth());
            result.addProperty("hunger", player.getFoodData().getFoodLevel());
            result.addProperty("armor", player.getArmorValue());

            // Dimension
            result.addProperty("dimension",
                    client.level.dimension().identifier().toString());

            // Gamemode
            if (client.gameMode != null) {
                result.addProperty("gamemode",
                        client.gameMode.getPlayerMode().getSerializedName());
            }

            // Extended player attributes
            result.addProperty("xp_level", player.experienceLevel);
            result.addProperty("xp_total", player.totalExperience);
            result.addProperty("saturation", player.getFoodData().getSaturationLevel());
            result.addProperty("air", player.getAirSupply());
            result.addProperty("max_air", player.getMaxAirSupply());
            result.addProperty("fire_ticks", player.getRemainingFireTicks());
            result.addProperty("frozen_ticks", player.getTicksFrozen());
            result.addProperty("absorption", player.getAbsorptionAmount());
            result.addProperty("on_fire", player.isOnFire());
            result.addProperty("in_water", player.isUnderWater());
            result.addProperty("in_lava", player.isInLava());
            result.addProperty("score", player.getScore());

            // Vehicle info
            Entity vehicle = player.getVehicle();
            if (vehicle != null) {
                JsonObject v = new JsonObject();
                String vType = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                        .getKey(vehicle.getType()).toString();
                v.addProperty("type", vType);
                if (vehicle instanceof LivingEntity lv) {
                    v.addProperty("health", lv.getHealth());
                    v.addProperty("max_health", lv.getMaxHealth());
                }
                result.add("vehicle", v);
            }
        }

        // Emmatone status
        boolean isPathing = EmmatoneAPI.getProvider().getPrimaryEmmatone()
                .getPathingBehavior().isPathing();

        JsonObject emmatone = new JsonObject();
        emmatone.addProperty("installed", true);
        emmatone.addProperty("is_pathing", isPathing);
        result.add("emmatone", emmatone);

        return result;
    }
}
