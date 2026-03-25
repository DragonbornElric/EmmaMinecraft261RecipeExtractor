package com.emma.bridge.commands;

import com.emma.bridge.goap.BaseRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * WebSocket command: get_bases
 *
 * Returns all known base locations from the BaseRegistry.
 *
 * Params:
 *   "dimension": string (optional) — filter by dimension
 *
 * Response:
 *   "bases": [{name, dimension, x, y, z, radius, created_at}, ...]
 */
public class GetBasesHandler implements ICommandHandler {

    private final BaseRegistry registry;

    public GetBasesHandler(BaseRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String getCommand() {
        return "get_bases";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        JsonObject result = new JsonObject();

        String dimension = null;
        if (params != null && params.has("dimension") && !params.get("dimension").isJsonNull()) {
            dimension = params.get("dimension").getAsString();
        }

        List<BaseRegistry.Base> bases = registry.getBases(dimension);

        JsonArray arr = new JsonArray();
        for (BaseRegistry.Base base : bases) {
            arr.add(base.toJson());
        }

        result.addProperty("status", "ok");
        result.add("bases", arr);
        result.addProperty("count", bases.size());
        return result;
    }
}
