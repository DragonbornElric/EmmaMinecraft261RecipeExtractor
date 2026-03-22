package com.emma.bridge.commands;

import com.emma.bridge.EmmaBridgeMod;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Routes incoming command names to their respective handlers.
 * Supports mode-awareness: camera mode disables Emmatone-dependent commands.
 */
public class CommandRouter {

    private final Map<String, ICommandHandler> handlers = new HashMap<>();
    private final Map<String, ICommandHandler> cameraHandlers = new HashMap<>();
    private final boolean cameraMode;

    public CommandRouter(boolean cameraMode) {
        this.cameraMode = cameraMode;
    }

    /**
     * Register a handler for player mode. Available when mode="player".
     */
    public void registerPlayerHandler(ICommandHandler handler) {
        handlers.put(handler.getCommand(), handler);
    }

    /**
     * Register a handler available in BOTH modes (player and camera).
     */
    public void registerSharedHandler(ICommandHandler handler) {
        handlers.put(handler.getCommand(), handler);
        cameraHandlers.put(handler.getCommand(), handler);
    }

    /**
     * Register a handler ONLY available in camera mode.
     */
    public void registerCameraHandler(ICommandHandler handler) {
        cameraHandlers.put(handler.getCommand(), handler);
    }

    /**
     * Dispatch a command to the appropriate handler.
     *
     * @param command The command name (e.g. "goto", "mine", "status")
     * @param params  The params object from the incoming JSON
     * @return The result data, or null if the command is unknown/unavailable
     * @throws IllegalArgumentException if the command is not available in the current mode
     */
    public JsonObject dispatch(String command, JsonObject params) {
        Map<String, ICommandHandler> activeHandlers = cameraMode ? cameraHandlers : handlers;
        ICommandHandler handler = activeHandlers.get(command);

        if (handler == null) {
            if (!cameraMode && cameraHandlers.containsKey(command)) {
                throw new IllegalArgumentException("Command '" + command + "' is only available in camera mode");
            }
            if (cameraMode && handlers.containsKey(command)) {
                throw new IllegalArgumentException("Command '" + command + "' is not available in camera mode (Emmatone required)");
            }
            throw new IllegalArgumentException("Unknown command: " + command);
        }

        EmmaBridgeMod.LOGGER.debug("[Emma Bridge] Dispatching command: {}", command);
        return handler.execute(params);
    }

    public boolean hasCommand(String command) {
        Map<String, ICommandHandler> activeHandlers = cameraMode ? cameraHandlers : handlers;
        return activeHandlers.containsKey(command);
    }

    /**
     * Get the handler for a command, or null if not found in the current mode.
     * Used by MessageHandler to check for IAsyncCommandHandler.
     */
    public ICommandHandler getHandler(String command) {
        Map<String, ICommandHandler> activeHandlers = cameraMode ? cameraHandlers : handlers;
        return activeHandlers.get(command);
    }

    public boolean isCameraMode() {
        return cameraMode;
    }
}
