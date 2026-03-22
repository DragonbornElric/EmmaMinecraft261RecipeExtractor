package com.emma.bridge.commands;

import com.emma.bridge.catalogue.ItemRecipeRegistry;
import com.emma.bridge.goap.GoalSet;
import com.emma.bridge.goap.GoapTicker;
import com.google.gson.JsonObject;

/**
 * WebSocket command: add_goal
 *
 * Adds a single goal without replacing existing ones.
 * If a goal with the same ID exists, it is replaced (upsert).
 *
 * Params:
 *   "id": string (required)
 *   "type": string (default "have_item")
 *   "priority": float (default 10.0)
 *   "target": { "item": "minecraft:diamond", "count": 3 }
 *
 * Returns: { "status": "ok", "goal_id": "...", "all_goals": [...] }
 */
public class AddGoalHandler implements ICommandHandler {

    private final GoalSet goalSet;
    private final GoapTicker ticker;

    public AddGoalHandler(GoalSet goalSet, GoapTicker ticker) {
        this.goalSet = goalSet;
        this.ticker = ticker;
    }

    @Override
    public String getCommand() {
        return "add_goal";
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
        String type = params.has("type") ? params.get("type").getAsString() : "have_item";
        float priority = params.has("priority") ? params.get("priority").getAsFloat() : 10.0f;
        JsonObject target = params.has("target") ? params.getAsJsonObject("target") : null;

        // Don't allow overriding survival goals
        if (GoalSet.Goal.isSurvivalId(id)) {
            result.addProperty("status", "error");
            result.addProperty("error", "Cannot override survival goal: " + id);
            return result;
        }

        // Validate have_item goals
        if ("have_item".equals(type) && target != null && target.has("item")) {
            String itemId = target.get("item").getAsString();
            String cleanId = itemId.contains(":") ? itemId.split(":")[1] : itemId;
            if (!ItemRecipeRegistry.isValidItem(cleanId)) {
                result.addProperty("status", "error");
                result.addProperty("error", "Unknown item: " + cleanId +
                        ". Use Minecraft drop names (e.g. 'raw_iron' not 'iron_ore').");
                return result;
            }
        }

        GoalSet.Goal goal = new GoalSet.Goal(id, type, priority, target);
        goalSet.addDynamicGoal(goal);
        ticker.triggerDecomposition();

        result.addProperty("status", "ok");
        result.addProperty("goal_id", id);
        result.addProperty("dynamic_goals", goalSet.getDynamicGoalIds().size());
        result.addProperty("derived_goals", goalSet.getDerivedGoals().size());
        result.add("all_goals", goalSet.toJson());
        return result;
    }
}
