package com.emma.bridge.commands;

import com.emma.bridge.goap.BaseRegistry;
import com.google.gson.JsonObject;

/**
 * WebSocket command: remove_base
 *
 * Removes a named base from the BaseRegistry.
 *
 * Params:
 *   "name": string       — base label to remove
 *   "dimension": string  — (optional) dimension, defaults to "minecraft:overworld"
 */
public class RemoveBaseHandler implements ICommandHandler {

    private final BaseRegistry registry;

    public RemoveBaseHandler(BaseRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String getCommand() {
        return "remove_base";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        JsonObject result = new JsonObject();

        if (params == null || !params.has("name")) {
            result.addProperty("status", "error");
            result.addProperty("error", "Missing required field: name");
            return result;
        }

        String name = params.get("name").getAsString();
        String dimension = params.has("dimension")
                ? params.get("dimension").getAsString()
                : "minecraft:overworld";

        boolean removed = registry.removeBase(name, dimension);

        result.addProperty("status", "ok");
        result.addProperty("removed", removed);
        return result;
    }
}
