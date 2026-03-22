package com.emma.bridge.websocket;

import com.emma.bridge.EmmaBridgeMod;
import com.emma.bridge.commands.CommandRouter;
import com.emma.bridge.commands.IAsyncCommandHandler;
import com.emma.bridge.commands.ICommandHandler;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.WebSocket;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Parses incoming JSON messages and dispatches commands to the
 * {@link CommandRouter} via a tick-based queue.
 *
 * Thread safety: The WebSocket server calls {@link #handle} on its own thread.
 * Commands are queued and processed during the next client tick via
 * {@link #processPendingCommands()}, which the mod's tick callback invokes.
 *
 * This avoids deadlocks that occur when Emmatone API methods are called from
 * {@code Minecraft.getInstance().execute()} — Emmatone needs the game
 * loop running, but execute() pauses the loop while the task runs.
 */
public class MessageHandler {

    private final CommandRouter router;

    /** Queued commands waiting to be dispatched on the next client tick. */
    private final ConcurrentLinkedQueue<PendingCommand> commandQueue = new ConcurrentLinkedQueue<>();

    public MessageHandler(CommandRouter router) {
        this.router = router;
    }

    /**
     * Handle a raw JSON message from a WebSocket client.
     * Parses the envelope, extracts command + params, queues for tick dispatch.
     */
    public void handle(WebSocket conn, String rawMessage) {
        JsonObject msg;
        try {
            msg = JsonParser.parseString(rawMessage).getAsJsonObject();
        } catch (Exception e) {
            EmmaBridgeMod.LOGGER.error("[Emma Bridge] Invalid JSON: {}", rawMessage);
            conn.send(JsonProtocol.error(null, "Invalid JSON").toString());
            return;
        }

        String id = msg.has("id") ? msg.get("id").getAsString() : null;
        String type = msg.has("type") ? msg.get("type").getAsString() : "command";

        // Handle ping immediately (no client thread needed)
        if ("ping".equals(type) || "ping".equals(getCommand(msg))) {
            JsonObject pong = new JsonObject();
            pong.addProperty("type", "pong");
            if (id != null) pong.addProperty("id", id);
            pong.addProperty("timestamp", System.currentTimeMillis());
            conn.send(pong.toString());
            return;
        }

        String command = getCommand(msg);
        if (command == null) {
            conn.send(JsonProtocol.error(id, "Missing 'command' field").toString());
            return;
        }

        JsonObject params = msg.has("params") ? msg.getAsJsonObject("params") : new JsonObject();

        // Queue for processing on the next client tick (not execute()!)
        commandQueue.add(new PendingCommand(conn, id, command, params));
    }

    /**
     * Process all pending commands. Called from the mod's END_CLIENT_TICK
     * callback, which runs inside the normal game loop — safe for Emmatone.
     */
    public void processPendingCommands() {
        PendingCommand cmd;
        while ((cmd = commandQueue.poll()) != null) {
            final PendingCommand c = cmd;
            try {
                // Check for async handlers (e.g. OverflowHandler) that must NOT
                // block the tick thread — they send C2S packets and need the main
                // thread free to receive S2C responses.
                ICommandHandler handler = router.getHandler(c.command);
                if (handler instanceof IAsyncCommandHandler asyncHandler) {
                    CompletableFuture<JsonObject> future = asyncHandler.executeAsync(c.params);
                    future.whenComplete((result, ex) -> {
                        if (ex != null) {
                            EmmaBridgeMod.LOGGER.error("[Emma Bridge] Async command '{}' failed",
                                    c.command, ex);
                            safeSend(c.conn, JsonProtocol.error(c.id,
                                    "Internal error: " + ex.getMessage()).toString());
                        } else {
                            safeSend(c.conn, JsonProtocol.accepted(c.id, result).toString());
                        }
                    });
                } else {
                    // Synchronous path (existing behavior for all other commands)
                    JsonObject result = router.dispatch(c.command, c.params);
                    safeSend(c.conn, JsonProtocol.accepted(c.id, result).toString());
                }
            } catch (IllegalArgumentException e) {
                safeSend(c.conn, JsonProtocol.error(c.id, e.getMessage()).toString());
            } catch (Throwable e) {
                EmmaBridgeMod.LOGGER.error("[Emma Bridge] Command '{}' failed", c.command, e);
                safeSend(c.conn, JsonProtocol.error(c.id, "Internal error: " + e.getMessage()).toString());
            }
        }
    }

    /**
     * Send a message to a WebSocket connection, ignoring errors if the
     * connection has already been closed (prevents game crash).
     */
    private void safeSend(WebSocket conn, String message) {
        try {
            if (conn.isOpen()) {
                conn.send(message);
            }
        } catch (Exception e) {
            EmmaBridgeMod.LOGGER.warn("[Emma Bridge] Failed to send response (client disconnected)");
        }
    }

    private String getCommand(JsonObject msg) {
        if (msg.has("command")) return msg.get("command").getAsString();
        if (msg.has("type") && !"command".equals(msg.get("type").getAsString())) {
            return msg.get("type").getAsString();
        }
        return null;
    }

    /** A command waiting to be dispatched on the client tick thread. */
    private static class PendingCommand {
        final WebSocket conn;
        final String id;
        final String command;
        final JsonObject params;

        PendingCommand(WebSocket conn, String id, String command, JsonObject params) {
            this.conn = conn;
            this.id = id;
            this.command = command;
            this.params = params;
        }
    }
}
