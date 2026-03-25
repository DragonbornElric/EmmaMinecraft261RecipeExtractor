package com.emma.bridge.commands;

import com.emma.bridge.goap.BuildPlanRegistry;
import com.emma.bridge.goap.GoalSet;
import com.emma.bridge.goap.GoapTicker;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Map;

/**
 * WebSocket command: set_build_goal
 *
 * Receives a build phase from Python and sets it up as a GOAP goal.
 * GoalDecomposer expands the materials into have_item subgoals,
 * and BuildStructureAction starts building once materials are gathered.
 *
 * Params:
 *   {
 *     "build_id": "house_phase_1",
 *     "name": "Oak House",
 *     "origin": {"x": 100, "y": 64, "z": 200},
 *     "blocks": [{"type": "minecraft:oak_planks", "x": 0, "y": 0, "z": 0}, ...],
 *     "materials": {"minecraft:oak_planks": 64, "minecraft:cobblestone": 128},
 *     "priority": 5.0
 *   }
 */
public class SetBuildGoalHandler implements ICommandHandler {

    private final GoalSet goalSet;
    private final GoapTicker ticker;
    private final BuildPlanRegistry buildPlanRegistry;

    public SetBuildGoalHandler(GoalSet goalSet, GoapTicker ticker, BuildPlanRegistry buildPlanRegistry) {
        this.goalSet = goalSet;
        this.ticker = ticker;
        this.buildPlanRegistry = buildPlanRegistry;
    }

    @Override
    public String getCommand() {
        return "set_build_goal";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        JsonObject result = new JsonObject();

        // Validate required fields
        if (params == null || !params.has("blocks") || !params.has("origin") || !params.has("materials")) {
            result.addProperty("status", "error");
            result.addProperty("error", "Missing required fields: blocks, origin, materials");
            return result;
        }

        String buildId = params.has("build_id") ? params.get("build_id").getAsString() : "build";
        String name = params.has("name") ? params.get("name").getAsString() : "Build";
        float priority = params.has("priority") ? params.get("priority").getAsFloat() : 5.0f;

        JsonObject origin = params.getAsJsonObject("origin");
        int ox = origin.get("x").getAsInt();
        int oy = origin.get("y").getAsInt();
        int oz = origin.get("z").getAsInt();

        JsonArray blocks = params.getAsJsonArray("blocks");
        JsonObject materialsJson = params.getAsJsonObject("materials");
        Map<String, Integer> materials = BuildPlanRegistry.parseMaterials(materialsJson);

        // Store build plan for BuildStructureAction
        BuildPlanRegistry.BuildPlan plan = new BuildPlanRegistry.BuildPlan(
                buildId, name, ox, oy, oz, blocks, materials, priority);
        buildPlanRegistry.setActivePlan(plan);

        // Create build_structure goal — materials embedded in target for GoalDecomposer
        JsonObject target = new JsonObject();
        target.addProperty("build_id", buildId);
        target.add("materials", materialsJson);

        GoalSet.Goal buildGoal = new GoalSet.Goal(
                "build_" + buildId, "build_structure", priority, target);
        goalSet.addDynamicGoal(buildGoal);

        // Decompose immediately so material subgoals appear
        ticker.triggerDecomposition();

        result.addProperty("status", "ok");
        result.addProperty("build_id", buildId);
        result.addProperty("block_count", blocks.size());
        result.addProperty("material_types", materials.size());
        result.addProperty("priority", priority);
        result.add("all_goals", goalSet.toJson());
        return result;
    }
}
