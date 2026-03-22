package com.emma.bridge.websocket;

import com.google.gson.JsonObject;

/**
 * Utility class for building well-formed JSON protocol messages.
 * Keeps the wire format consistent across all senders.
 */
public final class JsonProtocol {

    private JsonProtocol() {} // utility class

    // ─── Responses ──────────────────────────────────────────────────

    public static JsonObject response(String id, String status, JsonObject data) {
        JsonObject msg = new JsonObject();
        msg.addProperty("id", id);
        msg.addProperty("type", "response");
        msg.addProperty("status", status);
        if (data != null) msg.add("data", data);
        return msg;
    }

    public static JsonObject accepted(String id, JsonObject data) {
        return response(id, "accepted", data);
    }

    public static JsonObject error(String id, String errorMessage) {
        JsonObject msg = new JsonObject();
        msg.addProperty("id", id);
        msg.addProperty("type", "response");
        msg.addProperty("status", "error");
        msg.addProperty("error", errorMessage);
        return msg;
    }

    // ─── Events ─────────────────────────────────────────────────────

    public static JsonObject event(String eventName, JsonObject data) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "event");
        msg.addProperty("event", eventName);
        if (data != null) msg.add("data", data);
        msg.addProperty("timestamp", System.currentTimeMillis() / 1000.0);
        return msg;
    }
}
