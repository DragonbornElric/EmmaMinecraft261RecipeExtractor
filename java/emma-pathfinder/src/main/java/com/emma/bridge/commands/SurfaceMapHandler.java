package com.emma.bridge.commands;

import com.emma.bridge.EmmaBridgeMod;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Holder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

/**
 * Handles "surface_map" command — returns block types at the surface layer.
 *
 * For each (x, z) in the grid, returns the surface block type and Y.
 * Strips air and anything deeper than 1 block below surface.
 * Gives the LLM a complete picture of what's on the ground.
 *
 * Params: { "cx": int, "cz": int, "radius": int, "step": int (optional, default 1) }
 * Returns: { "blocks": [[{"b":"minecraft:grass_block","y":64}, ...], ...],
 *            "width": int, "depth": int, "center_x": int, "center_z": int,
 *            "min_y": int, "max_y": int }
 */
public class SurfaceMapHandler implements ICommandHandler {

    private static final int MAX_RADIUS = 40;

    @Override
    public String getCommand() {
        return "surface_map";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        Minecraft client = Minecraft.getInstance();
        Level world = client.level;
        if (world == null) {
            throw new RuntimeException("No world loaded");
        }

        int cx, cz;
        if (params.has("cx") && params.has("cz")) {
            cx = params.get("cx").getAsInt();
            cz = params.get("cz").getAsInt();
        } else if (client.player != null) {
            cx = client.player.blockPosition().getX();
            cz = client.player.blockPosition().getZ();
        } else {
            throw new RuntimeException("No cx/cz provided and no player available");
        }
        int radius = params.has("radius") ? Math.min(params.get("radius").getAsInt(), MAX_RADIUS) : 8;
        int step = params.has("step") ? Math.max(1, params.get("step").getAsInt()) : 1;
        boolean includeLight = params.has("include_light") && params.get("include_light").getAsBoolean();
        boolean includeBiome = params.has("include_biome") && params.get("include_biome").getAsBoolean();

        int minX = cx - radius;
        int maxX = cx + radius;
        int minZ = cz - radius;
        int maxZ = cz + radius;

        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;

        JsonArray rows = new JsonArray();

        for (int z = minZ; z <= maxZ; z += step) {
            JsonArray row = new JsonArray();
            for (int x = minX; x <= maxX; x += step) {
                int y = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                BlockPos pos = new BlockPos(x, y, z);
                BlockState state = world.getBlockState(pos);

                String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();

                JsonObject cell = new JsonObject();
                // Use short key names to reduce payload size
                cell.addProperty("b", blockId);
                cell.addProperty("y", y);

                // Include block state if it has properties (facing, half, etc.)
                if (!state.getProperties().isEmpty()) {
                    cell.addProperty("s", ScanHandler.stateToString(state));
                }

                // Opt-in: per-block light levels
                if (includeLight) {
                    cell.addProperty("l", world.getMaxLocalRawBrightness(pos));
                    cell.addProperty("sl", world.getBrightness(LightLayer.SKY, pos));
                    cell.addProperty("bl", world.getBrightness(LightLayer.BLOCK, pos));
                }

                // Opt-in: biome per block
                if (includeBiome) {
                    Holder<Biome> biome = world.getBiome(pos);
                    String biomeName = biome.unwrapKey().map(k -> k.identifier().toString())
                            .orElse("unknown");
                    cell.addProperty("biome", biomeName);
                }

                row.add(cell);

                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
            rows.add(row);
        }

        int width = (int) Math.ceil((double) (maxX - minX + 1) / step);
        int depth = (int) Math.ceil((double) (maxZ - minZ + 1) / step);

        EmmaBridgeMod.LOGGER.debug(
                "[Emma Bridge] SurfaceMap: {}x{} grid at ({},{}) radius={} step={}",
                width, depth, cx, cz, radius, step);

        JsonObject result = new JsonObject();
        result.add("blocks", rows);
        result.addProperty("width", width);
        result.addProperty("depth", depth);
        result.addProperty("center_x", cx);
        result.addProperty("center_z", cz);
        result.addProperty("min_y", minY == Integer.MAX_VALUE ? 0 : minY);
        result.addProperty("max_y", maxY == Integer.MIN_VALUE ? 0 : maxY);
        return result;
    }
}
