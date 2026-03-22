package com.emma.bridge.commands;

import com.emma.bridge.EmmaBridgeMod;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.Level;

/**
 * Handles "world_info" command — returns world time, weather, dimension, etc.
 *
 * Params: {} (none required)
 * Returns: { "time_of_day": long, "day_count": long, "weather": str,
 *            "difficulty": str, "dimension": str, "is_daytime": bool,
 *            "moon_phase": int }
 */
public class WorldInfoHandler implements ICommandHandler {

    @Override
    public String getCommand() {
        return "world_info";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        Minecraft mc = Minecraft.getInstance();
        Level world = mc.level;
        if (world == null) {
            throw new RuntimeException("No world loaded");
        }

        long timeOfDay = world.getDefaultClockTime() % 24000;
        long dayCount = world.getDefaultClockTime() / 24000;
        boolean isDaytime = timeOfDay < 12300 || timeOfDay > 23850;
        int moonPhase = (int) (dayCount % 8);

        // Weather
        String weather;
        if (world.isThundering()) {
            weather = "thunder";
        } else if (world.isRaining()) {
            weather = "rain";
        } else {
            weather = "clear";
        }

        // Difficulty
        String difficulty = world.getDifficulty().getSerializedName();

        // Dimension
        String dimension = world.dimension().identifier().toString();

        // Player light level (useful for survival decisions)
        int lightLevel = -1;
        int skyLight = -1;
        int blockLight = -1;
        LocalPlayer player = mc.player;
        if (player != null) {
            BlockPos pos = player.blockPosition();
            lightLevel = world.getMaxLocalRawBrightness(pos);
            skyLight = world.getBrightness(LightLayer.SKY, pos);
            blockLight = world.getBrightness(LightLayer.BLOCK, pos);
        }

        // Local difficulty (only available server-side in MC 26.1)
        double localDifficulty = -1;
        double regionalDifficulty = -1;

        // Biome temperature at player
        float biomeTemperature = Float.NaN;
        if (player != null) {
            try {
                biomeTemperature = world.getBiome(player.blockPosition()).value().getBaseTemperature();
            } catch (Exception ignored) {}
        }

        // Performance info
        int loadedChunks = world.getChunkSource().getLoadedChunksCount();
        int fps = mc.getFps();

        EmmaBridgeMod.LOGGER.debug(
                "[Emma Bridge] WorldInfo: day={}, time={}, weather={}, dim={}",
                dayCount, timeOfDay, weather, dimension);

        JsonObject result = new JsonObject();
        result.addProperty("time_of_day", timeOfDay);
        result.addProperty("day_count", dayCount);
        result.addProperty("is_daytime", isDaytime);
        result.addProperty("moon_phase", moonPhase);
        result.addProperty("weather", weather);
        result.addProperty("difficulty", difficulty);
        result.addProperty("dimension", dimension);
        result.addProperty("light_level", lightLevel);
        result.addProperty("sky_light", skyLight);
        result.addProperty("block_light", blockLight);
        result.addProperty("local_difficulty", localDifficulty);
        result.addProperty("regional_difficulty", regionalDifficulty);
        if (!Float.isNaN(biomeTemperature)) {
            result.addProperty("biome_temperature", biomeTemperature);
        }
        result.addProperty("loaded_chunks", loadedChunks);
        result.addProperty("fps", fps);
        return result;
    }
}
