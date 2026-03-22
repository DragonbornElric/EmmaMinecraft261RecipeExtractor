package com.emma.bridge;

import com.emma.bridge.websocket.MessageHandler;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket server that bridges Python orchestrator ↔ Minecraft/Emmatone.
 *
 * Protocol: JSON messages with { "type": "...", "id": "...", ... }
 * - Inbound (Python → MC):  commands like goto, mine, build, scan, cancel
 * - Outbound (MC → Python): events like position, health, task_complete, chat
 *
 * Delegates all message processing to {@link MessageHandler} which routes
 * commands to the appropriate handler on the Minecraft client thread.
 */
public class BridgeServer extends WebSocketServer {

    private static final Gson GSON = new Gson();
    private final MessageHandler messageHandler;

    /** Connected clients (typically just the one Python orchestrator). */
    private final Map<WebSocket, String> clients = new ConcurrentHashMap<>();

    public BridgeServer(int port, MessageHandler messageHandler) {
        super(new InetSocketAddress("127.0.0.1", port));
        this.messageHandler = messageHandler;
        setReuseAddr(true);
        setConnectionLostTimeout(30);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        clients.put(conn, conn.getRemoteSocketAddress().toString());
        EmmaBridgeMod.LOGGER.info("[Emma Bridge] Client connected: {}",
                conn.getRemoteSocketAddress());

        // Send hello with mod version and protocol info
        JsonObject hello = new JsonObject();
        hello.addProperty("type", "hello");
        hello.addProperty("mod_id", EmmaBridgeMod.MOD_ID);
        hello.addProperty("protocol_version", 1);
        hello.addProperty("mode", BridgeConfig.getMode());
        conn.send(GSON.toJson(hello));
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        clients.remove(conn);
        EmmaBridgeMod.LOGGER.info("[Emma Bridge] Client disconnected: {} (code={}, reason={})",
                conn.getRemoteSocketAddress(), code, reason);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        messageHandler.handle(conn, message);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        EmmaBridgeMod.LOGGER.error("[Emma Bridge] WebSocket error", ex);
    }

    @Override
    public void onStart() {
        EmmaBridgeMod.LOGGER.info("[Emma Bridge] WebSocket server is ready and listening.");
    }

    // ─── Public API ────────────────────────────────────────────────

    /**
     * Check if any clients are connected. Used by event reporters
     * to skip work when nobody is listening.
     */
    public boolean hasConnections() {
        return !clients.isEmpty();
    }

    /**
     * Broadcast an event to all connected clients.
     */
    public void broadcastEvent(JsonObject event) {
        if (clients.isEmpty()) return;
        String json = GSON.toJson(event);
        for (WebSocket conn : clients.keySet()) {
            if (conn.isOpen()) {
                conn.send(json);
            }
        }
    }
}
