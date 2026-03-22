package com.emma.bridge.schematic;

import com.emma.bridge.EmmaBridgeMod;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Converts a JSON array of build guide blocks (from Python BuildDB) into a
 * Emmatone ISchematic object in memory.
 *
 * This avoids needing actual .schematic/.schem files on the Minecraft side.
 * The Python orchestrator sends block data directly from the database.
 *
 * Input format (from Phase 28 BuildDB):
 * [
 *   {"type": "minecraft:oak_planks", "x": 0, "y": 0, "z": 0},
 *   {"type": "minecraft:oak_stairs", "x": 1, "y": 0, "z": 0, "state": "facing=north,half=bottom"},
 *   ...
 * ]
 *
 * Output: A Emmatone ISchematic (via AbstractSchematic subclass)
 */
public class GuideSchematicAdapter {

    /**
     * Convert JSON block array to a Emmatone ISchematic.
     *
     * Creates a concrete AbstractSchematic subclass that stores parsed
     * BlockState data in a 3D array and serves it via desiredState().
     *
     * @param blocks JSON array of block entries with type, x, y, z, and optional state
     * @return An ISchematic object
     */
    public Object fromGuideBlocks(JsonArray blocks) {
        // 1. Determine bounding box (handle negative offsets defensively)
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = 0, maxY = 0, maxZ = 0;
        for (JsonElement element : blocks) {
            JsonObject b = element.getAsJsonObject();
            int bx = b.get("x").getAsInt();
            int by = b.get("y").getAsInt();
            int bz = b.get("z").getAsInt();
            minX = Math.min(minX, bx);
            minY = Math.min(minY, by);
            minZ = Math.min(minZ, bz);
            maxX = Math.max(maxX, bx);
            maxY = Math.max(maxY, by);
            maxZ = Math.max(maxZ, bz);
        }

        // Shift coordinates if any are negative (Python should normalize, but guard here)
        int shiftX = minX < 0 ? -minX : 0;
        int shiftY = minY < 0 ? -minY : 0;
        int shiftZ = minZ < 0 ? -minZ : 0;
        if (shiftX > 0 || shiftY > 0 || shiftZ > 0) {
            EmmaBridgeMod.LOGGER.warn("[Emma Bridge] Negative offsets detected (min={},{},{}), shifting by ({},{},{}). " +
                    "Origin should be adjusted by Python caller.", minX, minY, minZ, shiftX, shiftY, shiftZ);
            maxX += shiftX;
            maxY += shiftY;
            maxZ += shiftZ;
        }

        int sizeX = maxX + 1;
        int sizeY = maxY + 1;
        int sizeZ = maxZ + 1;

        // 2. Build block state array [x][z][y] — Emmatone convention
        BlockState[][][] stateArray = new BlockState[sizeX][sizeZ][sizeY];
        // Initialize with air
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int y = 0; y < sizeY; y++) {
                    stateArray[x][z][y] = Blocks.AIR.defaultBlockState();
                }
            }
        }

        // 3. Parse each block
        int parsed = 0;
        int skipped = 0;
        int resolvedToAir = 0;
        for (JsonElement element : blocks) {
            JsonObject b = element.getAsJsonObject();
            int x = b.get("x").getAsInt() + shiftX;
            int y = b.get("y").getAsInt() + shiftY;
            int z = b.get("z").getAsInt() + shiftZ;
            String type = b.get("type").getAsString();
            String stateStr = b.has("state") ? b.get("state").getAsString() : null;

            // Skip invalid numeric block IDs (e.g. "minecraft:64" from old data)
            String path = type.contains(":") ? type.split(":", 2)[1] : type;
            if (path.matches("\\d+")) {
                EmmaBridgeMod.LOGGER.warn("[Emma Bridge] Skipping numeric block ID: {} at ({},{},{})",
                        type, x, y, z);
                skipped++;
                continue;
            }

            // Clean up state string: treat "{}", "null", empty as no state
            if (stateStr != null) {
                stateStr = stateStr.trim();
                if (stateStr.isEmpty() || stateStr.equals("{}") || stateStr.equals("null")) {
                    stateStr = null;
                }
            }

            BlockState state = parseBlockState(type, stateStr);

            // Double slabs are in-world block states (two half-slabs merged) but are
            // NOT obtainable as items.  Emmatone can't place them and skips the entire
            // layer, causing a permanent stuck.  Convert to the full-block equivalent
            // which is visually identical and can be placed normally.
            if (state.hasProperty(SlabBlock.TYPE) && state.getValue(SlabBlock.TYPE) == SlabType.DOUBLE) {
                BlockState fullBlock = resolveDoubleSlabToFullBlock(type);
                if (fullBlock != null) {
                    EmmaBridgeMod.LOGGER.info("[Schematic] Converted double slab {} → {} at ({},{},{})",
                            type, fullBlock.getBlock(), x, y, z);
                    state = fullBlock;
                } else {
                    // Fallback: change to bottom slab (half-height, better than stuck)
                    state = state.setValue(SlabBlock.TYPE, SlabType.BOTTOM);
                    EmmaBridgeMod.LOGGER.warn("[Schematic] Double slab {} has no full-block mapping, " +
                            "falling back to bottom slab at ({},{},{})", type, x, y, z);
                }
            }

            // Snow layers > 1 are in-world states created by stacking multiple
            // snow placements.  Emmatone can't do multi-step placement and reports
            // "Missing materials" for layers=2+.  Clamp to 1 so it places a single layer.
            if (state.hasProperty(SnowLayerBlock.LAYERS) && state.getValue(SnowLayerBlock.LAYERS) > 1) {
                int oldLayers = state.getValue(SnowLayerBlock.LAYERS);
                state = state.setValue(SnowLayerBlock.LAYERS, 1);
                EmmaBridgeMod.LOGGER.info("[Schematic] Clamped snow layers={} → 1 at ({},{},{})",
                        oldLayers, x, y, z);
            }

            // Check if block resolved to AIR (indicates unknown block type)
            if (state.isAir() && !type.contains("air")) {
                EmmaBridgeMod.LOGGER.warn("[Emma Bridge] Block '{}' resolved to AIR at ({},{},{})",
                        type, x, y, z);
                resolvedToAir++;
                continue; // Don't put air where we want a real block
            }

            if (x < sizeX && y < sizeY && z < sizeZ) {
                stateArray[x][z][y] = state;
                parsed++;
            }
        }

        EmmaBridgeMod.LOGGER.info("[Emma Bridge] Schematic: parsed {} blocks, skipped={}, resolvedToAir={}, size={}x{}x{}",
                parsed, skipped, resolvedToAir, sizeX, sizeY, sizeZ);

        // Log first few block types for debugging
        int logged = 0;
        for (int x = 0; x < sizeX && logged < 3; x++) {
            for (int z = 0; z < sizeZ && logged < 3; z++) {
                for (int y = 0; y < sizeY && logged < 3; y++) {
                    if (!stateArray[x][z][y].isAir()) {
                        EmmaBridgeMod.LOGGER.info("[Emma Bridge] Sample block [{},{},{}] = {}",
                                x, y, z, stateArray[x][z][y]);
                        logged++;
                    }
                }
            }
        }

        // 4. Create concrete AbstractSchematic subclass with block data
        GuideSchematic schematic = new GuideSchematic(stateArray, sizeX, sizeY, sizeZ);
        schematic.validateAndLog();
        return schematic;
    }

    /**
     * Parse a block type string and optional state string into a BlockState.
     *
     * @param type     Block identifier, e.g. "minecraft:oak_stairs"
     * @param stateStr State properties, e.g. "facing=north,half=bottom" or null
     * @return The resolved BlockState
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private BlockState parseBlockState(String type, String stateStr) {
        Block block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(type));
        BlockState state = block.defaultBlockState();

        if (stateStr != null && !stateStr.isEmpty()) {
            for (String prop : stateStr.split(",")) {
                String[] kv = prop.trim().split("=", 2);
                if (kv.length != 2) continue;

                Property<?> property = block.getStateDefinition().getProperty(kv[0].trim());
                if (property != null) {
                    state = applyProperty(state, property, kv[1].trim());
                } else {
                    EmmaBridgeMod.LOGGER.warn("[Emma Bridge] Unknown property '{}' for block '{}'",
                            kv[0], type);
                }
            }
        }
        return state;
    }

    /**
     * Apply a single property value to a block state.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private BlockState applyProperty(BlockState state, Property property, String value) {
        Optional<?> parsedValue = property.getValue(value);
        if (parsedValue.isPresent()) {
            return state.setValue(property, (Comparable) parsedValue.get());
        }
        EmmaBridgeMod.LOGGER.warn("[Emma Bridge] Invalid value '{}' for property '{}'",
                value, property.getName());
        return state;
    }

    // ── Double-slab → full-block mapping ─────────────────────────────────────

    /** Known special-case mappings where slab_name → full_block isn't just removing "_slab". */
    private static final Map<String, String> SLAB_TO_FULL_BLOCK = Map.ofEntries(
            // Wood: *_slab → *_planks
            Map.entry("oak_slab", "oak_planks"),
            Map.entry("spruce_slab", "spruce_planks"),
            Map.entry("birch_slab", "birch_planks"),
            Map.entry("jungle_slab", "jungle_planks"),
            Map.entry("acacia_slab", "acacia_planks"),
            Map.entry("dark_oak_slab", "dark_oak_planks"),
            Map.entry("mangrove_slab", "mangrove_planks"),
            Map.entry("cherry_slab", "cherry_planks"),
            Map.entry("crimson_slab", "crimson_planks"),
            Map.entry("warped_slab", "warped_planks"),
            Map.entry("bamboo_slab", "bamboo_planks"),
            Map.entry("bamboo_mosaic_slab", "bamboo_mosaic"),
            // Stone: special names
            Map.entry("stone_slab", "smooth_stone"),
            Map.entry("smooth_stone_slab", "smooth_stone"),
            Map.entry("brick_slab", "bricks"),
            Map.entry("nether_brick_slab", "nether_bricks"),
            Map.entry("red_nether_brick_slab", "red_nether_bricks"),
            Map.entry("quartz_slab", "quartz_block"),
            Map.entry("smooth_quartz_slab", "smooth_quartz"),
            Map.entry("prismarine_brick_slab", "prismarine_bricks"),
            Map.entry("cut_copper_slab", "cut_copper"),
            Map.entry("exposed_cut_copper_slab", "exposed_cut_copper"),
            Map.entry("weathered_cut_copper_slab", "weathered_cut_copper"),
            Map.entry("oxidized_cut_copper_slab", "oxidized_cut_copper"),
            Map.entry("waxed_cut_copper_slab", "waxed_cut_copper"),
            Map.entry("waxed_exposed_cut_copper_slab", "waxed_exposed_cut_copper"),
            Map.entry("waxed_weathered_cut_copper_slab", "waxed_weathered_cut_copper"),
            Map.entry("waxed_oxidized_cut_copper_slab", "waxed_oxidized_cut_copper")
    );

    /**
     * Resolve a double slab block type to its visually-equivalent full block.
     *
     * Double slabs can't be placed as items — they're formed by combining two
     * half-slabs in-world. Since Emmatone can't construct them, we substitute
     * the full block equivalent (e.g. spruce_slab[type=double] → spruce_planks).
     *
     * @param slabType The slab block identifier (e.g. "minecraft:spruce_slab")
     * @return The full block's default state, or null if unknown
     */
    private BlockState resolveDoubleSlabToFullBlock(String slabType) {
        String name = slabType.contains(":") ? slabType.split(":", 2)[1] : slabType;

        // Check explicit mapping first
        String fullName = SLAB_TO_FULL_BLOCK.get(name);
        if (fullName != null) {
            Block full = BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:" + fullName));
            if (full != Blocks.AIR) return full.defaultBlockState();
        }

        // Generic fallback: *_slab → * (cobblestone_slab → cobblestone, etc.)
        if (name.endsWith("_slab")) {
            String baseName = name.substring(0, name.length() - 5);
            Block full = BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:" + baseName));
            if (full != Blocks.AIR) return full.defaultBlockState();
        }

        return null;
    }
}
