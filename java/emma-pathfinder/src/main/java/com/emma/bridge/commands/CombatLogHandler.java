package com.emma.bridge.commands;

import com.emma.bridge.events.CombatLog;
import com.google.gson.JsonObject;

/**
 * Returns the combat log ring buffer contents for debugging.
 *
 * Params (optional):
 *   "last": int — return only the last N entries (default: all)
 *
 * Response: { "entries": [...], "count": 42 }
 */
public class CombatLogHandler implements ICommandHandler {

    @Override
    public String getCommand() {
        return "combat_log";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        JsonObject result = new JsonObject();
        CombatLog log = CombatLog.getInstance();

        int last = 0;
        if (params != null && params.has("last")) {
            last = params.get("last").getAsInt();
        }

        if (last > 0) {
            result.add("entries", log.snapshotLast(last));
        } else {
            result.add("entries", log.snapshot());
        }
        result.addProperty("count", log.size());

        return result;
    }
}
