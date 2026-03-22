package com.emma.bridge.commands;

import com.emma.bridge.BridgeConfig;
import com.emma.bridge.EmmaBridgeMod;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Holder;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

import java.util.Set;

/**
 * Handles "scan_area" command — returns non-filler blocks in a radius.
 *
 * Filters out air, stone, dirt, grass, water, lava, bedrock, etc.
 * Includes block state properties for oriented blocks.
 * Capped at maxScanBlocks from config.
 *
 * Params: { "x": int, "y": int, "z": int, "radius": int }
 * Returns: { "blocks": [...], "count": int, "capped": bool }
 */
public class ScanHandler implements ICommandHandler {

    private static final Set<Block> IGNORED_BLOCKS = Set.of(
            Blocks.AIR, Blocks.CAVE_AIR, Blocks.VOID_AIR,
            Blocks.STONE, Blocks.DIRT, Blocks.GRASS_BLOCK,
            Blocks.WATER, Blocks.LAVA, Blocks.BEDROCK,
            Blocks.DEEPSLATE, Blocks.SAND, Blocks.GRAVEL,
            Blocks.NETHERRACK, Blocks.END_STONE
    );

    private static final int MAX_RADIUS = 32;

    @Override
    public String getCommand() {
        return "scan_area";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        int cx = params.get("x").getAsInt();
        int cy = params.get("y").getAsInt();
        int cz = params.get("z").getAsInt();
        int radius = Math.min(params.get("radius").getAsInt(), MAX_RADIUS);
        int maxBlocks = BridgeConfig.getMaxScanBlocks();
        boolean includeLight = params.has("include_light") && params.get("include_light").getAsBoolean();
        boolean includeBiome = params.has("include_biome") && params.get("include_biome").getAsBoolean();
        boolean includeSignText = params.has("include_sign_text") && params.get("include_sign_text").getAsBoolean();

        Level world = Minecraft.getInstance().level;
        if (world == null) {
            throw new RuntimeException("No world loaded");
        }

        JsonArray results = new JsonArray();
        int count = 0;

        for (int x = cx - radius; x <= cx + radius && count < maxBlocks; x++) {
            for (int y = cy - radius; y <= cy + radius && count < maxBlocks; y++) {
                for (int z = cz - radius; z <= cz + radius && count < maxBlocks; z++) {
                    BlockPos pos = new BlockPos(x, y, z);

                    // Only scan loaded chunks
                    if (!world.isLoaded(pos)) continue;

                    BlockState state = world.getBlockState(pos);
                    if (!IGNORED_BLOCKS.contains(state.getBlock())) {
                        JsonObject entry = new JsonObject();
                        entry.addProperty("x", x);
                        entry.addProperty("y", y);
                        entry.addProperty("z", z);
                        entry.addProperty("block_type",
                                BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());

                        // Include block state properties for oriented blocks
                        if (!state.getProperties().isEmpty()) {
                            entry.addProperty("block_state", stateToString(state));
                        }

                        // Opt-in: per-block light levels
                        if (includeLight) {
                            entry.addProperty("light", world.getMaxLocalRawBrightness(pos));
                            entry.addProperty("sky_light", world.getBrightness(LightLayer.SKY, pos));
                            entry.addProperty("block_light", world.getBrightness(LightLayer.BLOCK, pos));
                        }

                        // Opt-in: biome per block
                        if (includeBiome) {
                            Holder<Biome> biome = world.getBiome(pos);
                            String biomeName = biome.unwrapKey().map(k -> k.identifier().toString())
                                    .orElse("unknown");
                            entry.addProperty("biome", biomeName);
                        }

                        // Opt-in: sign text
                        if (includeSignText && state.getBlock() instanceof SignBlock) {
                            String signText = HandlerUtils.extractSignText(world, pos);
                            if (signText != null) {
                                entry.addProperty("text", signText);
                            }
                        }

                        results.add(entry);
                        count++;
                    }
                }
            }
        }

        EmmaBridgeMod.LOGGER.debug("[Emma Bridge] Scan: {} blocks in radius {} at ({},{},{})",
                count, radius, cx, cy, cz);

        JsonObject result = new JsonObject();
        result.add("blocks", results);
        result.addProperty("count", count);
        result.addProperty("capped", count >= maxBlocks);
        return result;
    }

    /**
     * Convert block state properties to a string like "facing=north,half=top".
     */
    public static String stateToString(BlockState state) {
        StringBuilder sb = new StringBuilder();
        state.getValues().forEach(pv -> {
            if (sb.length() > 0) sb.append(",");
            sb.append(pv.property().getName()).append("=").append(pv.valueName());
        });
        return sb.toString();
    }

    /**
     * Convert block state properties to a JsonObject like {"facing":"north","half":"top"}.
     */
    public static JsonObject statePropsToJson(BlockState state) {
        JsonObject props = new JsonObject();
        state.getValues().forEach(pv -> {
            props.addProperty(pv.property().getName(), pv.valueName());
        });
        return props;
    }
}
