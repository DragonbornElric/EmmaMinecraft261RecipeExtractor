package com.emma.bridge.commands;

import com.emma.bridge.events.CombatLog;
import com.emma.bridge.goap.AgentDebugState;
import com.emma.bridge.goap.GoapTicker;
import com.google.gson.JsonObject;

/**
 * WebSocket command: agent_debug
 *
 * Returns the full GOAP scoring state from AgentDebugState singleton.
 * This is the primary tuning tool — consumed by watch_goap.py for live
 * terminal display and by emmatone_client.py for programmatic queries.
 *
 * Params (optional):
 *   "include_history": bool — include switch history (default: true)
 *   "include_world_state": bool — include full world state snapshot (default: false)
 *   "include_combat_log": int — include last N combat log entries (default: 5)
 *
 * Response: {
 *   "goap_enabled": true,
 *   "tick": 12345,
 *   "active_action": "EatFood",
 *   "hysteresis_bonus": 1.5,
 *   "auction": [
 *     {"action": "EatFood", "score": 8.5, "raw_score": 7.0, "is_active": true, "breakdown": {...}},
 *     {"action": "MineBlock", "score": 3.2, "raw_score": 3.2, "is_active": false, "breakdown": {...}},
 *     ...
 *   ],
 *   "goals": [
 *     {"id": "survive", "priority": 8, "score": 2.4},
 *     ...
 *   ],
 *   "world_state_diff": {"health": "20.0 -> 18.0"},
 *   "switch_history": [...],
 *   "combat_log": [...],
 *   "personality": {"safety": 0.5, "aggression": 0.5, ...}
 * }
 */
public class AgentDebugHandler implements ICommandHandler {

    private final GoapTicker ticker;

    public AgentDebugHandler(GoapTicker ticker) {
        this.ticker = ticker;
    }

    @Override
    public String getCommand() {
        return "agent_debug";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        JsonObject result = new JsonObject();

        // Handle control actions (enable/disable/log)
        if (params != null && params.has("action")) {
            String action = params.get("action").getAsString();
            switch (action) {
                case "enable" -> {
                    ticker.setEnabled(true);
                    result.addProperty("goap_enabled", true);
                    result.addProperty("status", "enabled");
                    return result;
                }
                case "disable" -> {
                    ticker.setEnabled(false);
                    result.addProperty("goap_enabled", false);
                    result.addProperty("status", "disabled");
                    return result;
                }
                case "log_on" -> {
                    ticker.setLoggingEnabled(true);
                    result.addProperty("logging_enabled", true);
                    result.addProperty("status", "logging_enabled");
                    return result;
                }
                case "log_off" -> {
                    ticker.setLoggingEnabled(false);
                    result.addProperty("logging_enabled", false);
                    result.addProperty("status", "logging_disabled");
                    return result;
                }
                // "snapshot" falls through to normal behavior below
            }
        }

        result.addProperty("goap_enabled", ticker.isEnabled());

        if (!ticker.isEnabled()) {
            result.addProperty("status", "goap_disabled");
            return result;
        }

        // Parse options
        boolean includeHistory = true;
        boolean includeWorldState = false;
        int combatLogEntries = 5;

        if (params != null) {
            if (params.has("include_history")) {
                includeHistory = params.get("include_history").getAsBoolean();
            }
            if (params.has("include_world_state")) {
                includeWorldState = params.get("include_world_state").getAsBoolean();
            }
            if (params.has("include_combat_log")) {
                combatLogEntries = params.get("include_combat_log").getAsInt();
            }
        }

        // Read AgentDebugState singleton (populated by UtilityScorer each tick)
        AgentDebugState debug = AgentDebugState.getInstance();
        JsonObject debugJson = debug.toJson(includeHistory);

        // Merge debug state into result
        for (var entry : debugJson.entrySet()) {
            result.add(entry.getKey(), entry.getValue());
        }

        // Add world state snapshot if requested
        if (includeWorldState && debug.lastWorldState != null) {
            result.add("world_state", debug.lastWorldState.toJson());
        }

        // Add combat log (last N entries)
        if (combatLogEntries > 0) {
            CombatLog combatLog = CombatLog.getInstance();
            result.add("combat_log", combatLog.snapshotLast(combatLogEntries));
        }

        // Add personality weights
        if (ticker.getScorer() != null) {
            JsonObject personality = new JsonObject();
            for (var entry : ticker.getScorer().getPersonalityWeights().entrySet()) {
                personality.addProperty(entry.getKey(), entry.getValue());
            }
            result.add("personality", personality);
        }

        // Add strategy knowledge summary
        if (ticker.getScorer() != null) {
            result.add("strategy_knowledge", ticker.getScorer().getGlobalKnowledge().toJson());
        }

        return result;
    }
}
