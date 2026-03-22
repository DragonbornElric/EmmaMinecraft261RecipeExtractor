package com.emma.bridge.commands;

import emmatone.api.EmmatoneAPI;
import emmatone.api.process.IBuilderProcess;
import com.emma.bridge.EmmaBridgeMod;
import com.emma.bridge.schematic.GuideSchematic;
import com.emma.bridge.schematic.GuideSchematicAdapter;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.Vec3i;

/**
 * Handles "build" command — receives a block list from Python BuildDB and
 * starts a build task via Emmatone's IBuilderProcess.
 *
 * Params: { "blocks": [...], "origin": {"x","y","z"}, "name": string }
 * Returns: { "task_id", "build_name", "block_count" }
 */
public class BuildHandler implements ICommandHandler {

    @Override
    public String getCommand() {
        return "build";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        JsonArray blocks = params.getAsJsonArray("blocks");
        if (blocks == null || blocks.isEmpty()) {
            throw new IllegalArgumentException("build: 'blocks' array is required and must not be empty");
        }

        JsonObject origin = params.getAsJsonObject("origin");
        if (origin == null) {
            throw new IllegalArgumentException("build: 'origin' object with x, y, z is required");
        }

        int ox = origin.get("x").getAsInt();
        int oy = origin.get("y").getAsInt();
        int oz = origin.get("z").getAsInt();
        String name = params.has("name") ? params.get("name").getAsString() : "build";

        // Convert JSON blocks → GuideSchematic via existing adapter
        GuideSchematicAdapter adapter = new GuideSchematicAdapter();
        GuideSchematic schematic = (GuideSchematic) adapter.fromGuideBlocks(blocks);

        // Configure Emmatone builder for structural builds
        var settings = EmmatoneAPI.getSettings();
        settings.buildInLayers.value = true;
        settings.layerHeight.value = 2;
        settings.buildIgnoreDirection.value = true;

        // Start build via Emmatone's builder process
        IBuilderProcess builder = EmmatoneAPI.getProvider()
                .getPrimaryEmmatone().getBuilderProcess();
        builder.build(name, schematic, new Vec3i(ox, oy, oz));

        EmmaBridgeMod.LOGGER.info("[Emma Bridge] Build started: '{}' ({} blocks) at ({},{},{})",
                name, blocks.size(), ox, oy, oz);

        String taskId = TaskRegistry.registerTask("build");

        JsonObject result = new JsonObject();
        result.addProperty("task_id", taskId);
        result.addProperty("command", "build");
        result.addProperty("build_name", name);
        result.addProperty("block_count", blocks.size());
        return result;
    }
}
