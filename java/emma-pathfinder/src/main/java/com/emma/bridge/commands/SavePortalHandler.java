package com.emma.bridge.commands;

import com.emma.bridge.goap.PortalRegistry;
import com.google.gson.JsonObject;

/**
 * WebSocket command: save_portal
 *
 * Saves a portal location to the PortalRegistry (persisted to JSON).
 * Can be called by Python/Emma or any WebSocket consumer.
 *
 * Params:
 *   "name": string       — portal label (e.g., "base_portal", "fortress_portal")
 *   "type": string       — "nether_portal" or "end_portal"
 *   "dimension": string  — dimension where the portal is located
 *   "x": int, "y": int, "z": int — block coordinates
 */
public class SavePortalHandler implements ICommandHandler {

    private final PortalRegistry registry;

    public SavePortalHandler(PortalRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String getCommand() {
        return "save_portal";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        JsonObject result = new JsonObject();

        if (params == null) {
            result.addProperty("status", "error");
            result.addProperty("error", "Missing params");
            return result;
        }

        // Required fields
        String name = params.has("name") ? params.get("name").getAsString() : null;
        String type = params.has("type") ? params.get("type").getAsString() : "nether_portal";
        String dimension = params.has("dimension") ? params.get("dimension").getAsString() : null;

        if (name == null || dimension == null) {
            result.addProperty("status", "error");
            result.addProperty("error", "Missing required fields: name, dimension");
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

        PortalRegistry.Portal portal = registry.addPortal(name, type, dimension, x, y, z);

        result.addProperty("status", "ok");
        result.add("portal", portal.toJson());
        return result;
    }
}
