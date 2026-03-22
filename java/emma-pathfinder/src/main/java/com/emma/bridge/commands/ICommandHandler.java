package com.emma.bridge.commands;

import com.google.gson.JsonObject;

/**
 * Interface for all command handlers in the bridge mod.
 * Each handler translates one JSON command type into Minecraft/Emmatone actions.
 */
public interface ICommandHandler {

    /** The command name this handler responds to (e.g. "goto", "mine", "build"). */
    String getCommand();

    /**
     * Execute the command with the given parameters.
     *
     * IMPORTANT: This method is always called on the Minecraft client thread
     * (via Minecraft.execute()), so it is safe to access game state directly.
     *
     * @param params  The "params" object from the incoming JSON command
     * @return A JSON result object to send back, or null for no response data
     */
    JsonObject execute(JsonObject params);
}
