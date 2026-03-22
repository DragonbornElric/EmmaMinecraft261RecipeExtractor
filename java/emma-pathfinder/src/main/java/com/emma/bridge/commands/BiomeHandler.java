package com.emma.bridge.commands;

import com.emma.bridge.EmmaBridgeMod;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

import java.util.HashMap;
import java.util.Map;

/**
 * Handles "biome" command — returns biome info at a point or across a radius.
 *
 * Single point: { "biome": "minecraft:plains" }
 * With radius:  { "biomes": {"minecraft:plains": 85, "minecraft:river": 15},
 *                 "dominant": "minecraft:plains", "sample_count": 100 }
 *
 * Params: { "x": int, "z": int, "radius": int (optional) }
 */
public class BiomeHandler implements ICommandHandler {

    private static final int MAX_RADIUS = 40;
    private static final int SAMPLE_STEP = 4;  // sample every N blocks for efficiency

    @Override
    public String getCommand() {
        return "biome";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        Minecraft client = Minecraft.getInstance();
        Level world = client.level;
        if (world == null) {
            throw new RuntimeException("No world loaded");
        }

        // Default to player position when x/z not provided
        int x, z;
        if (params.has("x") && params.has("z")) {
            x = params.get("x").getAsInt();
            z = params.get("z").getAsInt();
        } else if (client.player != null) {
            x = client.player.blockPosition().getX();
            z = client.player.blockPosition().getZ();
        } else {
            throw new RuntimeException("No x/z provided and no player available");
        }

        // Single point lookup
        if (!params.has("radius") || params.get("radius").getAsInt() <= 0) {
            String biomeName = getBiomeName(world, x, 64, z);

            EmmaBridgeMod.LOGGER.debug("[Emma Bridge] Biome at ({},{}): {}",
                    x, z, biomeName);

            JsonObject result = new JsonObject();
            result.addProperty("biome", biomeName);

            // Opt-in: temperature and downfall
            boolean includeTemp = params.has("include_temperature")
                    && params.get("include_temperature").getAsBoolean();
            if (includeTemp) {
                try {
                    Biome biome = world.getBiome(new BlockPos(x, 64, z)).value();
                    result.addProperty("temperature", biome.getBaseTemperature());
                } catch (Exception ignored) {}
            }

            return result;
        }

        // Radius survey — sample biomes across the area
        int radius = Math.min(params.get("radius").getAsInt(), MAX_RADIUS);
        Map<String, Integer> biomeCount = new HashMap<>();
        int totalSamples = 0;

        for (int sx = x - radius; sx <= x + radius; sx += SAMPLE_STEP) {
            for (int sz = z - radius; sz <= z + radius; sz += SAMPLE_STEP) {
                String biomeName = getBiomeName(world, sx, 64, sz);
                biomeCount.merge(biomeName, 1, Integer::sum);
                totalSamples++;
            }
        }

        // Find dominant biome
        String dominant = biomeCount.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("unknown");

        // Convert counts to percentages
        JsonObject biomes = new JsonObject();
        for (Map.Entry<String, Integer> entry : biomeCount.entrySet()) {
            int pct = Math.round(100f * entry.getValue() / totalSamples);
            biomes.addProperty(entry.getKey(), pct);
        }

        EmmaBridgeMod.LOGGER.debug(
                "[Emma Bridge] Biome survey at ({},{}) radius={}: {} biomes, dominant={}",
                x, z, radius, biomeCount.size(), dominant);

        JsonObject result = new JsonObject();
        result.add("biomes", biomes);
        result.addProperty("dominant", dominant);
        result.addProperty("sample_count", totalSamples);

        // Opt-in: temperature per biome
        boolean includeTemp = params.has("include_temperature")
                && params.get("include_temperature").getAsBoolean();
        if (includeTemp) {
            JsonObject temps = new JsonObject();
            for (String biomeName : biomeCount.keySet()) {
                try {
                    // Sample temperature from center of area for each biome type
                    Holder<Biome> biomeEntry = world.getBiome(new BlockPos(x, 64, z));
                    temps.addProperty(biomeName, biomeEntry.value().getBaseTemperature());
                } catch (Exception ignored) {}
            }
            if (temps.size() > 0) {
                result.add("temperatures", temps);
            }
        }

        return result;
    }

    private String getBiomeName(Level world, int x, int y, int z) {
        Holder<Biome> biomeEntry = world.getBiome(new BlockPos(x, y, z));
        return biomeEntry.unwrapKey().map(key -> key.identifier().toString())
                .orElse("unknown");
    }
}
