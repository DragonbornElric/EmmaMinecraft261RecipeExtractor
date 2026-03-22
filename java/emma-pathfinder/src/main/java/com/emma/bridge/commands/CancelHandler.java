package com.emma.bridge.commands;

import emmatone.api.EmmatoneAPI;
import com.emma.bridge.EmmaBridgeMod;
import com.google.gson.JsonObject;

/**
 * Handles "cancel" command — cancels the currently active Emmatone process.
 *
 * Params: {} (none)
 * Returns: {}
 */
public class CancelHandler implements ICommandHandler {

    @Override
    public String getCommand() {
        return "cancel";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        EmmatoneAPI.getProvider().getPrimaryEmmatone()
                .getPathingBehavior()
                .cancelEverything();

        // Clear the task registry so TaskListener doesn't emit task_complete
        TaskRegistry.clearTask();
        EmmaBridgeMod.LOGGER.info("[Emma Bridge] Cancel: all Emmatone processes cancelled");

        return new JsonObject(); // empty success
    }
}
