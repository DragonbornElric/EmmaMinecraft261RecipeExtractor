package com.emma.bridge.commands;

import emmatone.api.EmmatoneAPI;
import emmatone.api.pathing.goals.Goal;
import emmatone.api.pathing.goals.GoalBlock;
import emmatone.api.pathing.goals.GoalXZ;
import emmatone.api.process.ICustomGoalProcess;
import com.emma.bridge.EmmaBridgeMod;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Handles "goto" command — navigates the player to target coordinates via Emmatone.
 *
 * Params: { "x": int, "z": int, "y": int (optional — omit for XZ-only goal) }
 * Returns: { "task_id": string }
 */
public class GotoHandler implements ICommandHandler {

    @Override
    public String getCommand() {
        return "goto";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        int x = params.get("x").getAsInt();
        int z = params.get("z").getAsInt();

        // Y is optional — if missing or null, use GoalXZ (surface-level navigation)
        JsonElement yElem = params.get("y");
        boolean hasY = yElem != null && !yElem.isJsonNull();
        int y = hasY ? yElem.getAsInt() : 0;

        ICustomGoalProcess goalProcess = EmmatoneAPI.getProvider()
                .getPrimaryEmmatone()
                .getCustomGoalProcess();

        Goal goal;
        if (hasY) {
            goal = new GoalBlock(x, y, z);
        } else {
            goal = new GoalXZ(x, z);
        }
        goalProcess.setGoalAndPath(goal);

        if (hasY) {
            EmmaBridgeMod.LOGGER.info("[Emma Bridge] Goto: navigating to ({}, {}, {})", x, y, z);
        } else {
            EmmaBridgeMod.LOGGER.info("[Emma Bridge] Goto: navigating to ({}, ~, {})", x, z);
        }

        String taskId = TaskRegistry.registerTask("goto");
        JsonObject result = new JsonObject();
        result.addProperty("task_id", taskId);
        result.addProperty("command", "goto");
        result.addProperty("x", x);
        if (hasY) result.addProperty("y", y);
        result.addProperty("z", z);
        return result;
    }
}
