package com.emma.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Configuration for the Emma Bridge Mod.
 *
 * Loads from {@code config/emma_bridge.json} in the Minecraft game directory.
 * Creates a default config file on first run. All values have sensible defaults.
 *
 * Mode-aware: player mode includes Emmatone/travel settings, camera mode
 * includes preset distances and lerp speed.
 */
public class BridgeConfig {

    private static final String CONFIG_FILE = "emma_bridge.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ─── Configurable values (loaded from JSON) ────────────────────

    /** WebSocket server port. */
    private static int port = 8765;

    /** Mode: "player" (with Emmatone) or "camera" (spectator teleport only). */
    private static String mode = "player";

    /** Port of the player bridge — used by camera mode to connect for position tracking. */
    private static int playerPort = 8765;

    /** Position update interval (ms). */
    private static long positionUpdateIntervalMs = 500;

    /** Minimum distance moved before emitting position_update (blocks). */
    private static float positionDeadzone = 0.1f;

    /** Nearby entity scan interval (ms). */
    private static long entityScanIntervalMs = 2000;

    /** Nearby entity scan radius (blocks). */
    private static int entityScanRadius = 32;

    /** Inventory change debounce (ms). */
    private static long inventoryDebounceMs = 500;

    /** Maximum blocks returned per scan_area call. */
    private static int maxScanBlocks = 50000;

    /** Heartbeat interval (ms) — periodic state updates. */
    private static long heartbeatIntervalMs = 2000;

    /** Event throttle window (ms). */
    private static long eventThrottleMs = 250;

    // ─── Player-only settings ────────────────────────────────────

    /** Enable travel look override — face direction of travel during goto. */
    private static boolean travelLookOverrideEnabled = true;

    /** Pitch angle during travel override (degrees, negative = look down). */
    private static float travelLookPitch = -5.0f;

    // ─── GOAP planner settings (player-only) ─────────────────────
    /** Enable GOAP utility AI planner (runs alongside EmmaClef during transition). */
    private static boolean goapEnabled = false;

    // ─── Panic teleport settings (player-only) ──────────────────
    private static boolean panicTeleportEnabled = false;
    private static float panicThreshold = 4.0f;
    private static double panicSafeX = 0;
    private static double panicSafeY = 0;
    private static double panicSafeZ = 0;
    private static long panicCooldownMs = 60000;

    // ─── Camera-only settings ────────────────────────────────────

    /** Camera preset overrides (loaded as raw JSON, applied to CameraPresets). */
    private static JsonObject cameraPresets = null;

    /** Camera lerp speed (smoothing rate per tick). */
    private static float cameraLerpSpeed = 0.15f;

    /** Camera teleport distance threshold (blocks). Camera /tp's if further than this from target. */
    private static float cameraTpDistance = 20.0f;

    // ─── Load / Save ───────────────────────────────────────────────

    /**
     * Load config from the Minecraft config directory.
     * Creates a default file if none exists.
     */
    public static void load() {
        Path configDir = Minecraft.getInstance().gameDirectory.toPath().resolve("config");
        Path configFile = configDir.resolve(CONFIG_FILE);

        try {
            if (Files.exists(configFile)) {
                String json = Files.readString(configFile);
                JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                applyJson(obj);
                EmmaBridgeMod.LOGGER.info("[Emma Bridge] Config loaded from {}", configFile);
            } else {
                // Create default config
                Files.createDirectories(configDir);
                Files.writeString(configFile, GSON.toJson(toJson()));
                EmmaBridgeMod.LOGGER.info("[Emma Bridge] Default config created at {}", configFile);
            }
        } catch (IOException e) {
            EmmaBridgeMod.LOGGER.error("[Emma Bridge] Failed to load config, using defaults", e);
        }
    }

    private static void applyJson(JsonObject obj) {
        if (obj.has("port")) port = obj.get("port").getAsInt();
        if (obj.has("mode")) mode = obj.get("mode").getAsString();
        if (obj.has("player_port")) playerPort = obj.get("player_port").getAsInt();
        if (obj.has("position_update_interval_ms"))
            positionUpdateIntervalMs = obj.get("position_update_interval_ms").getAsLong();
        if (obj.has("position_deadzone"))
            positionDeadzone = obj.get("position_deadzone").getAsFloat();
        if (obj.has("entity_scan_interval_ms"))
            entityScanIntervalMs = obj.get("entity_scan_interval_ms").getAsLong();
        if (obj.has("entity_scan_radius"))
            entityScanRadius = obj.get("entity_scan_radius").getAsInt();
        if (obj.has("inventory_debounce_ms"))
            inventoryDebounceMs = obj.get("inventory_debounce_ms").getAsLong();
        if (obj.has("max_scan_blocks"))
            maxScanBlocks = obj.get("max_scan_blocks").getAsInt();
        if (obj.has("heartbeat_interval_ms"))
            heartbeatIntervalMs = obj.get("heartbeat_interval_ms").getAsLong();
        if (obj.has("event_throttle_ms"))
            eventThrottleMs = obj.get("event_throttle_ms").getAsLong();

        // Player-only
        if (obj.has("travel_look_override_enabled"))
            travelLookOverrideEnabled = obj.get("travel_look_override_enabled").getAsBoolean();
        if (obj.has("travel_look_pitch"))
            travelLookPitch = obj.get("travel_look_pitch").getAsFloat();

        // GOAP planner
        if (obj.has("goap_enabled"))
            goapEnabled = obj.get("goap_enabled").getAsBoolean();

        // Panic teleport
        if (obj.has("panic_teleport_enabled"))
            panicTeleportEnabled = obj.get("panic_teleport_enabled").getAsBoolean();
        if (obj.has("panic_threshold"))
            panicThreshold = obj.get("panic_threshold").getAsFloat();
        if (obj.has("panic_safe_x"))
            panicSafeX = obj.get("panic_safe_x").getAsDouble();
        if (obj.has("panic_safe_y"))
            panicSafeY = obj.get("panic_safe_y").getAsDouble();
        if (obj.has("panic_safe_z"))
            panicSafeZ = obj.get("panic_safe_z").getAsDouble();
        if (obj.has("panic_cooldown_ms"))
            panicCooldownMs = obj.get("panic_cooldown_ms").getAsLong();

        // Camera-only
        if (obj.has("camera_presets") && obj.get("camera_presets").isJsonObject())
            cameraPresets = obj.getAsJsonObject("camera_presets");
        if (obj.has("camera_lerp_speed"))
            cameraLerpSpeed = obj.get("camera_lerp_speed").getAsFloat();
        if (obj.has("camera_tp_distance"))
            cameraTpDistance = obj.get("camera_tp_distance").getAsFloat();
    }

    private static JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("port", port);
        obj.addProperty("mode", mode);
        obj.addProperty("player_port", playerPort);
        obj.addProperty("position_update_interval_ms", positionUpdateIntervalMs);
        obj.addProperty("position_deadzone", positionDeadzone);
        obj.addProperty("entity_scan_interval_ms", entityScanIntervalMs);
        obj.addProperty("entity_scan_radius", entityScanRadius);
        obj.addProperty("inventory_debounce_ms", inventoryDebounceMs);
        obj.addProperty("max_scan_blocks", maxScanBlocks);
        obj.addProperty("heartbeat_interval_ms", heartbeatIntervalMs);
        obj.addProperty("event_throttle_ms", eventThrottleMs);

        // Mode-specific fields
        if (isCameraMode()) {
            obj.addProperty("camera_lerp_speed", cameraLerpSpeed);
            obj.addProperty("camera_tp_distance", cameraTpDistance);
            // Camera presets written as nested object if present
            if (cameraPresets != null) {
                obj.add("camera_presets", cameraPresets);
            }
        } else {
            obj.addProperty("travel_look_override_enabled", travelLookOverrideEnabled);
            obj.addProperty("travel_look_pitch", travelLookPitch);
            obj.addProperty("goap_enabled", goapEnabled);
            obj.addProperty("panic_teleport_enabled", panicTeleportEnabled);
            obj.addProperty("panic_threshold", panicThreshold);
            obj.addProperty("panic_safe_x", panicSafeX);
            obj.addProperty("panic_safe_y", panicSafeY);
            obj.addProperty("panic_safe_z", panicSafeZ);
            obj.addProperty("panic_cooldown_ms", panicCooldownMs);
        }

        return obj;
    }

    // ─── Getters ───────────────────────────────────────────────────

    public static int getPort() { return port; }
    public static String getMode() { return mode; }
    public static boolean isCameraMode() { return "camera".equals(mode); }
    public static int getPlayerPort() { return playerPort; }
    public static long getPositionUpdateIntervalMs() { return positionUpdateIntervalMs; }
    public static float getPositionDeadzone() { return positionDeadzone; }
    public static long getEntityScanIntervalMs() { return entityScanIntervalMs; }
    public static int getEntityScanRadius() { return entityScanRadius; }
    public static long getInventoryDebounceMs() { return inventoryDebounceMs; }
    public static int getMaxScanBlocks() { return maxScanBlocks; }
    public static long getHeartbeatIntervalMs() { return heartbeatIntervalMs; }
    public static long getEventThrottleMs() { return eventThrottleMs; }
    public static boolean isTravelLookOverrideEnabled() { return travelLookOverrideEnabled; }
    public static float getTravelLookPitch() { return travelLookPitch; }
    public static JsonObject getCameraPresets() { return cameraPresets; }
    public static float getCameraLerpSpeed() { return cameraLerpSpeed; }
    public static float getCameraTpDistance() { return cameraTpDistance; }
    public static boolean isGoapEnabled() { return goapEnabled; }
    public static boolean isPanicTeleportEnabled() { return panicTeleportEnabled; }
    public static float getPanicThreshold() { return panicThreshold; }
    public static double getPanicSafeX() { return panicSafeX; }
    public static double getPanicSafeY() { return panicSafeY; }
    public static double getPanicSafeZ() { return panicSafeZ; }
    public static long getPanicCooldownMs() { return panicCooldownMs; }
}
