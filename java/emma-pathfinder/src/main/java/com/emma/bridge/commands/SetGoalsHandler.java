package com.emma.bridge.commands;

import com.emma.bridge.catalogue.ItemRecipeRegistry;
import com.emma.bridge.goap.GoalSet;
import com.emma.bridge.goap.GoapTicker;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * WebSocket command: set_goals
 *
 * Sets dynamic GOAP goals from Python/Emma.
 *
 * Params:
 *   "goals": [
 *     {"id": "get_diamonds", "type": "have_item", "priority": 10,
 *      "target": {"item": "minecraft:diamond", "count": 3}},
 *     ...
 *   ]
 *   "enabled": true/false  — optional, toggles GOAP on/off at runtime
 *
 * Survival goals (survive, stay_fed, be_lit) are always active and
 * cannot be overridden.
 */
public class SetGoalsHandler implements ICommandHandler {

    private final GoalSet goalSet;
    private final GoapTicker ticker;

    public SetGoalsHandler(GoalSet goalSet, GoapTicker ticker) {
        this.goalSet = goalSet;
        this.ticker = ticker;
    }

    @Override
    public String getCommand() {
        return "set_goals";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        JsonObject result = new JsonObject();

        if (params == null) {
            result.addProperty("status", "error");
            result.addProperty("error", "Missing params");
            return result;
        }

        // Runtime GOAP toggle
        if (params.has("enabled")) {
            boolean enable = params.get("enabled").getAsBoolean();
            ticker.setEnabled(enable);
            result.addProperty("goap_enabled", enable);
        }

        if (!params.has("goals")) {
            // Allow enable-only calls (no goals array required)
            if (params.has("enabled")) {
                result.addProperty("status", "ok");
                result.add("all_goals", goalSet.toJson());
                return result;
            }
            result.addProperty("status", "error");
            result.addProperty("error", "Missing 'goals' array");
            return result;
        }

        JsonArray goalsArray = params.getAsJsonArray("goals");
        List<GoalSet.Goal> newGoals = new ArrayList<>();
        List<String> unknownItems = new ArrayList<>();

        for (var element : goalsArray) {
            JsonObject g = element.getAsJsonObject();
            String id = g.has("id") ? g.get("id").getAsString() : "unnamed";
            String type = g.has("type") ? g.get("type").getAsString() : "generic";
            float priority = g.has("priority") ? g.get("priority").getAsFloat() : 5.0f;
            JsonObject target = g.has("target") ? g.getAsJsonObject("target") : null;

            // Don't allow overriding survival goals
            if (GoalSet.Goal.isSurvivalId(id)) continue;

            // Validate have_item goals — item must exist in ItemRecipeRegistry
            if ("have_item".equals(type) && target != null && target.has("item")) {
                String itemId = target.get("item").getAsString();
                String cleanId = itemId.contains(":") ? itemId.split(":")[1] : itemId;
                if (!ItemRecipeRegistry.isValidItem(cleanId)) {
                    unknownItems.add(cleanId);
                    continue;
                }
            }

            newGoals.add(new GoalSet.Goal(id, type, priority, target));
        }

        if (!unknownItems.isEmpty()) {
            result.addProperty("status", "error");
            result.addProperty("error", "Unknown item(s) not in recipe registry: " +
                    String.join(", ", unknownItems) +
                    ". Use Minecraft drop names (e.g. 'raw_iron' not 'iron_ore', 'raw_gold' not 'gold_ore').");
            result.add("rejected_items", new Gson().toJsonTree(unknownItems));
            if (!newGoals.isEmpty()) {
                goalSet.setDynamicGoals(newGoals);
                ticker.triggerDecomposition();
                result.addProperty("valid_goals_set", newGoals.size());
                result.add("all_goals", goalSet.toJson());
            }
            return result;
        }

        goalSet.setDynamicGoals(newGoals);

        // Immediately decompose goals into subgoals (no debounce — goals just changed)
        ticker.triggerDecomposition();

        result.addProperty("status", "ok");
        result.addProperty("dynamic_goals_set", newGoals.size());
        result.addProperty("derived_goals", goalSet.getDerivedGoals().size());
        result.add("all_goals", goalSet.toJson());
        return result;
    }
}
