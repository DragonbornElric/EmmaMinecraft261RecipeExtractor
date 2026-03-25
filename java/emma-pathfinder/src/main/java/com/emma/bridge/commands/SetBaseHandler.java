package com.emma.bridge.commands;

import com.emma.bridge.goap.BaseRegistry;
import com.google.gson.JsonObject;

/**
 * WebSocket command: set_base
 *
 * Saves a named base location to the BaseRegistry (persisted to JSON).
 * The GOAP agent uses registered bases as fallback navigation targets
 * when food is unavailable underground.
 *
 * Params:
 *   "name": string       — base label (e.g., "main", "forge", "farm")
 *   "x": int, "y": int, "z": int — block coordinates
 *   "dimension": string  — (optional) dimension, defaults to "minecraft:overworld"
 *   "radius": int        — (optional) base radius in blocks, defaults to 32
 */
public class SetBaseHandler implements ICommandHandler {

    private final BaseRegistry registry;

    public SetBaseHandler(BaseRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String getCommand() {
        return "set_base";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        JsonObject result = new JsonObject();

        if (params == null) {
            result.addProperty("status", "error");
            result.addProperty("error", "Missing params");
            return result;
        }

        String name = params.has("name") ? params.get("name").getAsString() : null;
        if (name == null || name.isEmpty()) {
            result.addProperty("status", "error");
            result.addProperty("error", "Missing required field: name");
            return result;
        }

        if (!params.has("x") || !params.has("y") || !params.has("z")) {
            result.addProperty("status", "error");
            result.addProperty("error", "Missing required fields: x, y, z");
            return result;
        }

        int x = params.get("x").getAsInt();
        int y = params.get("y").getAsInt();
        int z = params.get("z").getAsInt();
        String dimension = params.has("dimension")
                ? params.get("dimension").getAsString()
                : "minecraft:overworld";
        int radius = params.has("radius") ? params.get("radius").getAsInt() : 32;

        BaseRegistry.Base base = registry.addBase(name, dimension, x, y, z, radius);

        result.addProperty("status", "ok");
        result.add("base", base.toJson());
        return result;
    }
}
