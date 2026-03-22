package com.emma.bridge.commands;

import com.emma.bridge.EmmaBridgeMod;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.Level;

/**
 * Handles "heightmap" command — returns a 2D array of surface Y values.
 *
 * Uses Minecraft's native WORLD_SURFACE heightmap for efficient lookup.
 * Supports optional downsampling via "step" parameter.
 *
 * Params: { "cx": int, "cz": int, "radius": int, "step": int (optional, default 1) }
 * Returns: { "heights": [[y, ...], ...], "min_y": int, "max_y": int, "avg_y": float,
 *            "width": int, "depth": int, "center_x": int, "center_z": int }
 */
public class HeightmapHandler implements ICommandHandler {

    private static final int MAX_RADIUS = 40;

    @Override
    public String getCommand() {
        return "heightmap";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        Minecraft client = Minecraft.getInstance();
        Level world = client.level;
        if (world == null) {
            throw new RuntimeException("No world loaded");
        }

        // Default to player position when cx/cz not provided
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

        int minX = cx - radius;
        int maxX = cx + radius;
        int minZ = cz - radius;
        int maxZ = cz + radius;

        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        long sumY = 0;
        int count = 0;

        JsonArray rows = new JsonArray();

        for (int z = minZ; z <= maxZ; z += step) {
            JsonArray row = new JsonArray();
            for (int x = minX; x <= maxX; x += step) {
                int y = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                row.add(y);

                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
                sumY += y;
                count++;
            }
            rows.add(row);
        }

        float avgY = count > 0 ? (float) sumY / count : 0;
        int width = (int) Math.ceil((double) (maxX - minX + 1) / step);
        int depth = (int) Math.ceil((double) (maxZ - minZ + 1) / step);

        EmmaBridgeMod.LOGGER.debug(
                "[Emma Bridge] Heightmap: {}x{} grid at ({},{}) radius={} step={}",
                width, depth, cx, cz, radius, step);

        JsonObject result = new JsonObject();
        result.add("heights", rows);
        result.addProperty("min_y", minY);
        result.addProperty("max_y", maxY);
        result.addProperty("avg_y", avgY);
        result.addProperty("width", width);
        result.addProperty("depth", depth);
        result.addProperty("center_x", cx);
        result.addProperty("center_z", cz);
        result.addProperty("min_x", minX);
        result.addProperty("min_z", minZ);
        return result;
    }
}
