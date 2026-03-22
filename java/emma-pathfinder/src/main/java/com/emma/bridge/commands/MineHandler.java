package com.emma.bridge.commands;

import emmatone.api.EmmatoneAPI;
import com.emma.bridge.EmmaBridgeMod;
import com.google.gson.JsonObject;

/**
 * Handles "mine" command — tells Emmatone to mine a specific block type.
 *
 * Uses mineByName(int, String...) which is a core Emmatone API method
 * that handles block name resolution internally — avoids Block registry
 * lookup issues across MC versions.
 *
 * Params: { "block_type": "minecraft:oak_log", "quantity": 64 }
 * Returns: { "task_id": string }
 */
public class MineHandler implements ICommandHandler {

    @Override
    public String getCommand() {
        return "mine";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        String blockType = params.get("block_type").getAsString();
        int quantity = params.has("quantity") ? params.get("quantity").getAsInt() : 64;

        EmmatoneAPI.getProvider().getPrimaryEmmatone()
                .getMineProcess()
                .mineByName(quantity, blockType);

        EmmaBridgeMod.LOGGER.info("[Emma Bridge] Mine: mineByName {} x{}", blockType, quantity);

        String taskId = TaskRegistry.registerTask("mine");
        JsonObject result = new JsonObject();
        result.addProperty("task_id", taskId);
        result.addProperty("command", "mine");
        result.addProperty("block_type", blockType);
        result.addProperty("quantity", quantity);
        return result;
    }
}
