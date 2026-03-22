package com.emma.bridge.commands;

import com.emma.bridge.EmmaBridgeMod;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Handles "chat" command — sends chat messages or slash commands.
 * Available in both player and camera modes (shared handler).
 *
 * Params:
 *   { "message": "hello world" }         — sends as chat message
 *   { "command": "time set day" }         — sends as slash command (no leading /)
 *   { "command": "summon zombie ~ ~ ~" }  — summon entities, etc.
 *
 * Returns: { sent: true/false, type: "message"|"command", reason: string }
 */
public class ChatHandler implements ICommandHandler {

    @Override
    public String getCommand() {
        return "chat";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;

        JsonObject result = new JsonObject();

        if (player == null) {
            result.addProperty("sent", false);
            result.addProperty("reason", "no_player");
            return result;
        }

        if (player.connection == null) {
            result.addProperty("sent", false);
            result.addProperty("reason", "no_network");
            return result;
        }

        // Slash command (without leading /)
        if (params.has("command")) {
            String command = params.get("command").getAsString().trim();
            if (command.isEmpty()) {
                result.addProperty("sent", false);
                result.addProperty("reason", "empty_command");
                return result;
            }
            // Strip leading / if accidentally included
            if (command.startsWith("/")) {
                command = command.substring(1);
            }
            try {
                player.connection.sendCommand(command);
                EmmaBridgeMod.LOGGER.info("[Emma Bridge] Sent command: /{}", command);
                result.addProperty("sent", true);
                result.addProperty("type", "command");
                result.addProperty("command", command);
            } catch (Exception e) {
                EmmaBridgeMod.LOGGER.error("[Emma Bridge] Failed to send command: /{}", command, e);
                result.addProperty("sent", false);
                result.addProperty("reason", e.getMessage());
            }
            return result;
        }

        // Chat message
        if (params.has("message")) {
            String message = params.get("message").getAsString();
            if (message.isEmpty()) {
                result.addProperty("sent", false);
                result.addProperty("reason", "empty_message");
                return result;
            }
            try {
                player.connection.sendChat(message);
                EmmaBridgeMod.LOGGER.info("[Emma Bridge] Sent chat: {}", message);
                result.addProperty("sent", true);
                result.addProperty("type", "message");
                result.addProperty("message", message);
            } catch (Exception e) {
                EmmaBridgeMod.LOGGER.error("[Emma Bridge] Failed to send chat", e);
                result.addProperty("sent", false);
                result.addProperty("reason", e.getMessage());
            }
            return result;
        }

        result.addProperty("sent", false);
        result.addProperty("reason", "missing 'command' or 'message' parameter");
        return result;
    }
}
