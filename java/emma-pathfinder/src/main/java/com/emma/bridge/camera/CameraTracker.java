package com.emma.bridge.camera;

import com.emma.bridge.EmmaBridgeMod;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebSocket CLIENT that connects to Emma's bridge (player instance) to receive
 * position_update events, then teleports the local CameraBot account to a
 * computed camera position based on the active preset.
 *
 * Runs entirely in Java — no Python in the hot path.  Python only sends
 * rare {@code set_camera_preset} commands for scene/task transitions.
 *
 * Lifecycle: created by {@link com.emma.bridge.EmmaBridgeClient} in camera mode.
 * <ul>
 *   <li>{@link #start()} — connects the WS client to Emma's bridge</li>
 *   <li>{@link #tick()} — called every MC client tick, applies queued position</li>
 *   <li>{@link #stop()} — disconnects and cleans up</li>
 * </ul>
 */
public class CameraTracker {

    private static final Gson GSON = new Gson();
    private static final float DEADZONE_SQ = 0.01f;  // 0.1 block squared

    private final int playerPort;
    private volatile WebSocketClient wsClient;
    private volatile boolean running;
    private final AtomicBoolean reconnectPending = new AtomicBoolean(false);

    // Emma's latest position (written by WS thread, read by tick thread)
    private volatile double emmaX, emmaY, emmaZ;
    private volatile float emmaYaw, emmaPitch;
    private volatile boolean positionDirty;

    // Smoothing rate per tick (0.01=very slow, 1.0=instant snap). Configurable via configure_camera command.
    private volatile float lerpSpeed = 0.15f;

    // Teleport distance threshold squared (blocks²). Camera /tp's when further than this from target.
    // Default 20 blocks (400.0 squared). Configurable via emma_bridge.json and configure_camera command.
    private volatile double tpDistanceSq = 400.0;

    // Active camera preset
    private volatile CameraPresets.Preset activePreset;

    // Target camera position (recomputed when Emma moves)
    private double targetX, targetY, targetZ;
    private float targetYaw, targetPitch;

    // Current smoothed camera position (lerped toward target each tick)
    private double currentX, currentY, currentZ;
    private float currentYaw, currentPitch;

    private volatile boolean initialized = false;

    public CameraTracker(int playerPort) {
        this.playerPort = playerPort;
        this.activePreset = CameraPresets.get(CameraPresets.DEFAULT_PRESET);
    }

    // ── Lifecycle ────────────────────────────────────────────

    /** Connect to Emma's bridge WebSocket server. */
    public void start() {
        if (running) return;
        running = true;
        connect();
    }

    /** Disconnect and stop tracking. */
    public void stop() {
        running = false;
        if (wsClient != null) {
            try {
                wsClient.closeBlocking();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            wsClient = null;
        }
        EmmaBridgeMod.LOGGER.info("[CameraTracker] Stopped");
    }

    /**
     * Called every MC client tick from the render thread.
     * Recomputes target when Emma moves, then smoothly lerps toward it.
     */
    public void tick() {
        if (!running) return;

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;

        CameraPresets.Preset preset = activePreset;
        if (preset == null) return;

        // Recompute target position when new data arrives
        if (positionDirty) {
            positionDirty = false;

            // Snapshot volatile fields
            double ex = emmaX, ey = emmaY, ez = emmaZ;
            float yaw = emmaYaw;

            // Compute camera world position from preset offsets
            double yawRad = Math.toRadians(yaw);

            // Minecraft: -z is forward at yaw=0
            double fwdX = -Math.sin(yawRad);
            double fwdZ = Math.cos(yawRad);

            // Right vector (perpendicular, 90° clockwise)
            double rightX = -fwdZ;
            double rightZ = fwdX;

            targetX = ex + fwdX * preset.offsetForward() + rightX * preset.offsetRight();
            targetY = ey + preset.offsetUp();
            targetZ = ez + fwdZ * preset.offsetForward() + rightZ * preset.offsetRight();

            // Camera looks at Emma + yaw offset
            double lookDx = ex - targetX;
            double lookDz = ez - targetZ;
            targetYaw = (float) Math.toDegrees(Math.atan2(-lookDx, lookDz)) + preset.yawOffset();
            targetPitch = preset.pitch();

            // First update — snap immediately, no lerp
            if (!initialized) {
                initialized = true;
                currentX = targetX;
                currentY = targetY;
                currentZ = targetZ;
                currentYaw = targetYaw;
                currentPitch = targetPitch;
            }
        }

        if (!initialized) return;

        // If camera is too far from where it should be, /tp directly
        double actualX = player.getX(), actualY = player.getY(), actualZ = player.getZ();
        double dx = actualX - targetX;
        double dy = actualY - targetY;
        double dz = actualZ - targetZ;
        double distSq = dx * dx + dy * dy + dz * dz;

        if (distSq > tpDistanceSq && player.connection != null) {
            String tpCmd = String.format(java.util.Locale.US,
                    "tp @s %.2f %.2f %.2f %.1f %.1f",
                    targetX, targetY, targetZ, targetYaw, targetPitch);
            player.connection.sendCommand(tpCmd);
            currentX = targetX;
            currentY = targetY;
            currentZ = targetZ;
            currentYaw = targetYaw;
            currentPitch = targetPitch;
            EmmaBridgeMod.LOGGER.info("[CameraTracker] Camera too far (dist={}) — /tp to {}, {}, {}",
                    String.format("%.1f", Math.sqrt(distSq)), targetX, targetY, targetZ);
            return;
        }

        // Lerp current position toward target (small distances — server accepts)
        currentX += (targetX - currentX) * lerpSpeed;
        currentY += (targetY - currentY) * lerpSpeed;
        currentZ += (targetZ - currentZ) * lerpSpeed;
        currentYaw = lerpAngle(currentYaw, targetYaw, lerpSpeed);
        currentPitch += (targetPitch - currentPitch) * lerpSpeed;

        // Apply to player entity
        player.setPos(currentX, currentY, currentZ);
        player.setYRot(currentYaw % 360f);
        player.setXRot(Math.max(-90f, Math.min(90f, currentPitch)));
    }

    /** Lerp between two angles (degrees) via shortest path. */
    private static float lerpAngle(float from, float to, float t) {
        float diff = ((to - from + 180f) % 360f) - 180f;
        return from + diff * t;
    }

    // ── Configuration ─────────────────────────────────────────

    /** Set the per-tick smoothing rate. Clamped to [0.01, 1.0]. */
    public void setLerpSpeed(float speed) {
        this.lerpSpeed = Math.max(0.01f, Math.min(1.0f, speed));
        EmmaBridgeMod.LOGGER.info("[CameraTracker] Lerp speed set to {}", this.lerpSpeed);
    }

    public float getLerpSpeed() {
        return lerpSpeed;
    }

    /** Set the teleport distance threshold (blocks). Clamped to [2.0, 128.0]. */
    public void setTpDistance(double blocks) {
        blocks = Math.max(2.0, Math.min(128.0, blocks));
        this.tpDistanceSq = blocks * blocks;
        EmmaBridgeMod.LOGGER.info("[CameraTracker] TP distance set to {} blocks", blocks);
    }

    public double getTpDistance() {
        return Math.sqrt(tpDistanceSq);
    }

    /** Force recomputation on next tick (called after preset config update). */
    public void forceRecompute() {
        positionDirty = true;
    }

    // ── Preset control ───────────────────────────────────────

    /** Switch to a named preset. Called from SetPresetHandler. */
    public void setPreset(String name) {
        CameraPresets.Preset preset = CameraPresets.get(name);
        if (preset == null) {
            EmmaBridgeMod.LOGGER.warn("[CameraTracker] Unknown preset: {}", name);
            return;
        }
        activePreset = preset;
        positionDirty = true;  // force recompute with new preset
        EmmaBridgeMod.LOGGER.info("[CameraTracker] Preset changed to: {}", name);
    }

    /** Get the currently active preset name. */
    public String getPresetName() {
        CameraPresets.Preset p = activePreset;
        return p != null ? p.name() : "none";
    }

    // ── WebSocket client ─────────────────────────────────────

    private void connect() {
        reconnectPending.set(false);  // allow future reconnects from this new connection

        // Close previous client if it exists (prevents orphaned connections)
        WebSocketClient old = wsClient;
        if (old != null) {
            try { old.close(); } catch (Exception ignored) {}
        }

        URI uri = URI.create("ws://127.0.0.1:" + playerPort);
        EmmaBridgeMod.LOGGER.info("[CameraTracker] Connecting to Emma's bridge at {}", uri);

        wsClient = new WebSocketClient(uri) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                EmmaBridgeMod.LOGGER.info("[CameraTracker] Connected to Emma's bridge");
            }

            @Override
            public void onMessage(String message) {
                handleMessage(message);
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                EmmaBridgeMod.LOGGER.info("[CameraTracker] Disconnected (code={}, reason={})",
                        code, reason);
                scheduleReconnect();
            }

            @Override
            public void onError(Exception ex) {
                EmmaBridgeMod.LOGGER.warn("[CameraTracker] Connection error: {}", ex.getMessage());
                scheduleReconnect();
            }
        };

        // Connect asynchronously (non-blocking)
        wsClient.connect();
    }

    private void handleMessage(String raw) {
        try {
            JsonObject msg = JsonParser.parseString(raw).getAsJsonObject();
            String type = msg.has("type") ? msg.get("type").getAsString() : "";

            if (!"event".equals(type)) return;

            String event = msg.has("event") ? msg.get("event").getAsString() : "";
            if (!"position_update".equals(event)) return;

            JsonObject data = msg.getAsJsonObject("data");
            if (data == null) return;

            emmaX = data.get("x").getAsDouble();
            emmaY = data.get("y").getAsDouble();
            emmaZ = data.get("z").getAsDouble();
            emmaYaw = data.get("yaw").getAsFloat();
            emmaPitch = data.get("pitch").getAsFloat();
            positionDirty = true;

        } catch (Exception e) {
            EmmaBridgeMod.LOGGER.debug("[CameraTracker] Failed to parse message: {}",
                    e.getMessage());
        }
    }

    private void scheduleReconnect() {
        if (!running) return;
        // CAS: only one reconnect in flight at a time
        if (!reconnectPending.compareAndSet(false, true)) return;
        Thread reconnect = new Thread(() -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                reconnectPending.set(false);
                return;
            }
            if (running) {
                EmmaBridgeMod.LOGGER.info("[CameraTracker] Reconnecting...");
                connect();
            } else {
                reconnectPending.set(false);
            }
        }, "CameraTracker-Reconnect");
        reconnect.setDaemon(true);
        reconnect.start();
    }
}
