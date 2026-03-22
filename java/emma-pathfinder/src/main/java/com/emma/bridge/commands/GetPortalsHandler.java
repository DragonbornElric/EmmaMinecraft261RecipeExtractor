package com.emma.bridge.commands;

import com.emma.bridge.goap.PortalRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * WebSocket command: get_portals
 *
 * Returns all known portal locations from the PortalRegistry.
 *
 * Params:
 *   "dimension": string (optional) — filter by dimension
 *
 * Response:
 *   "portals": [{name, type, dimension, x, y, z, created_at}, ...]
 */
public class GetPortalsHandler implements ICommandHandler {

    private final PortalRegistry registry;

    public GetPortalsHandler(PortalRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String getCommand() {
        return "get_portals";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        JsonObject result = new JsonObject();

        String dimension = null;
        if (params != null && params.has("dimension") && !params.get("dimension").isJsonNull()) {
            dimension = params.get("dimension").getAsString();
        }

        List<PortalRegistry.Portal> portals = registry.getPortals(dimension);

        JsonArray arr = new JsonArray();
        for (PortalRegistry.Portal portal : portals) {
            arr.add(portal.toJson());
        }

        result.addProperty("status", "ok");
        result.add("portals", arr);
        result.addProperty("count", portals.size());
        return result;
    }
}
