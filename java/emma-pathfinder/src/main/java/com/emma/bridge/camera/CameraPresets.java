package com.emma.bridge.camera;

import com.emma.bridge.EmmaBridgeMod;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Camera preset definitions for the spectator CameraBot.
 *
 * Each preset describes an offset relative to Emma's position and facing
 * direction.  The {@link CameraTracker} applies these offsets using
 * trigonometry to produce a world-space camera position.
 *
 * Hardcoded defaults are kept in {@link #DEFAULTS}.  At runtime, Python
 * can push overrides from {@code config.json} via the
 * {@code configure_camera} WebSocket command, which calls
 * {@link #applyOverrides(JsonObject)}.  The active preset map
 * ({@link #ACTIVE}) is a {@link ConcurrentHashMap} for thread-safe
 * reads from the render thread.
 */
public final class CameraPresets {

    /** Immutable preset record. */
    public record Preset(
            String name,
            float offsetForward,
            float offsetRight,
            float offsetUp,
            float pitch,
            float yawOffset
    ) {}

    /** Hardcoded defaults — never mutated. */
    private static final Map<String, Preset> DEFAULTS;

    /** Active presets — ConcurrentHashMap for thread-safe reads from render thread. */
    private static final ConcurrentHashMap<String, Preset> ACTIVE = new ConcurrentHashMap<>();

    static {
        Map<String, Preset> m = new LinkedHashMap<>();

        // Building
        m.put("overhead", new Preset("overhead",
                0f, 0f, 18f, -90f, 0f));
        m.put("build_closeup", new Preset("build_closeup",
                2f, 2f, 2f, -30f, 0f));

        // Combat
        m.put("combat_tight", new Preset("combat_tight",
                -4f, 0f, 2f, -15f, 0f));

        // Exploration
        m.put("side_track", new Preset("side_track",
                0f, 6f, 2f, 0f, 0f));
        m.put("wide_overhead", new Preset("wide_overhead",
                0f, 0f, 25f, -75f, 0f));

        // Mining
        m.put("mine_angle", new Preset("mine_angle",
                -5f, 0f, 3f, -20f, 0f));

        // Narrating / Idle
        m.put("front_face", new Preset("front_face",
                3f, 0f, 0f, 0f, 0f));

        // Goal complete — celebration
        m.put("celebration", new Preset("celebration",
                -8f, 0f, 5f, -15f, 0f));

        DEFAULTS = Map.copyOf(m);
        ACTIVE.putAll(DEFAULTS);
    }

    /** Get a preset by name from the active (possibly overridden) set. */
    public static Preset get(String name) {
        return ACTIVE.get(name);
    }

    /** All available preset names. */
    public static Set<String> names() {
        return ACTIVE.keySet();
    }

    /** The default preset used on startup. */
    public static final String DEFAULT_PRESET = "front_face";

    /**
     * Apply partial overrides from a JSON "presets" object.
     * Each key is a preset name; values are objects with optional
     * fields: forward, right, up, pitch, yaw_offset.
     * Missing fields inherit from the hardcoded default.
     * Unknown preset names are ignored (no dynamic creation).
     */
    public static void applyOverrides(JsonObject presetsObj) {
        int applied = 0;
        for (Map.Entry<String, JsonElement> entry : presetsObj.entrySet()) {
            String name = entry.getKey();
            Preset base = DEFAULTS.get(name);
            if (base == null) {
                EmmaBridgeMod.LOGGER.warn(
                        "[CameraPresets] Ignoring unknown preset override: {}", name);
                continue;
            }
            JsonObject ov = entry.getValue().getAsJsonObject();
            Preset merged = new Preset(
                    name,
                    ov.has("forward")    ? ov.get("forward").getAsFloat()    : base.offsetForward(),
                    ov.has("right")      ? ov.get("right").getAsFloat()      : base.offsetRight(),
                    ov.has("up")         ? ov.get("up").getAsFloat()         : base.offsetUp(),
                    ov.has("pitch")      ? ov.get("pitch").getAsFloat()      : base.pitch(),
                    ov.has("yaw_offset") ? ov.get("yaw_offset").getAsFloat() : base.yawOffset()
            );
            ACTIVE.put(name, merged);
            applied++;
            EmmaBridgeMod.LOGGER.info(
                    "[CameraPresets] Override: {} → fwd={}, right={}, up={}, pitch={}, yawOff={}",
                    name, merged.offsetForward(), merged.offsetRight(),
                    merged.offsetUp(), merged.pitch(), merged.yawOffset());
        }
        EmmaBridgeMod.LOGGER.info("[CameraPresets] {} preset override(s) applied", applied);
    }

    /** Reset all presets to hardcoded defaults. */
    public static void resetToDefaults() {
        ACTIVE.clear();
        ACTIVE.putAll(DEFAULTS);
        EmmaBridgeMod.LOGGER.info("[CameraPresets] Reset to defaults");
    }

    private CameraPresets() {}  // utility class
}
