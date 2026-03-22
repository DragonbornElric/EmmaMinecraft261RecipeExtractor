package com.emma.bridge.commands;

import emmatone.api.EmmatoneAPI;
import com.emma.bridge.EmmaBridgeMod;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

/**
 * Handles "farm" command — starts Emmatone's FarmProcess to harvest/replant crops.
 *
 * Params: { "range": 100 }
 *     or: { "range": 100, "x": 100, "y": 64, "z": 200 }
 * Returns: { "task_id": string, "command": "farm", "range": int }
 */
public class FarmHandler implements ICommandHandler {

    @Override
    public String getCommand() {
        return "farm";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        int range = params.has("range") ? params.get("range").getAsInt() : 0;

        BlockPos center = HandlerUtils.extractBlockPos(params);
        if (center != null) {
            EmmatoneAPI.getProvider().getPrimaryEmmatone()
                    .getFarmProcess().farm(range, center);
        } else {
            EmmatoneAPI.getProvider().getPrimaryEmmatone()
                    .getFarmProcess().farm(range);
        }

        EmmaBridgeMod.LOGGER.info("[Emma Bridge] Farm: range={}, center={}", range, center);

        String taskId = TaskRegistry.registerTask("farm");
        JsonObject result = new JsonObject();
        result.addProperty("task_id", taskId);
        result.addProperty("command", "farm");
        result.addProperty("range", range);
        return result;
    }
}
