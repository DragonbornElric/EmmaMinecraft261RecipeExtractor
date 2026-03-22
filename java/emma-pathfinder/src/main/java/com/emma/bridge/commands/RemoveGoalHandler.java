package com.emma.bridge.commands;

import com.emma.bridge.goap.GoalSet;
import com.emma.bridge.goap.GoapTicker;
import com.google.gson.JsonObject;

/**
 * WebSocket command: remove_goal
 *
 * Removes a single goal by ID. Does not affect other goals.
 *
 * Params:
 *   "id": string (required) — the goal ID to remove
 *
 * Returns: { "status": "ok", "removed": true/false, "all_goals": [...] }
 */
public class RemoveGoalHandler implements ICommandHandler {

    private final GoalSet goalSet;
    private final GoapTicker ticker;

    public RemoveGoalHandler(GoalSet goalSet, GoapTicker ticker) {
        this.goalSet = goalSet;
        this.ticker = ticker;
    }

    @Override
    public String getCommand() {
        return "remove_goal";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        JsonObject result = new JsonObject();

        if (params == null || !params.has("id")) {
            result.addProperty("status", "error");
            result.addProperty("error", "Missing 'id' field");
            return result;
        }

        String id = params.get("id").getAsString();

        if (GoalSet.Goal.isSurvivalId(id)) {
            result.addProperty("status", "error");
            result.addProperty("error", "Cannot remove survival goal: " + id);
            return result;
        }

        boolean removed = goalSet.removeDynamicGoal(id);
        if (removed) {
            ticker.triggerDecomposition();
        }

        result.addProperty("status", "ok");
        result.addProperty("removed", removed);
        result.addProperty("dynamic_goals", goalSet.getDynamicGoalIds().size());
        result.addProperty("derived_goals", goalSet.getDerivedGoals().size());
        result.add("all_goals", goalSet.toJson());
        return result;
    }
}
