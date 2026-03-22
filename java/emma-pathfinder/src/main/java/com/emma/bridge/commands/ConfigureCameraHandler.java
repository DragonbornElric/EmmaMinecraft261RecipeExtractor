package com.emma.bridge.commands;

import com.emma.bridge.camera.CameraPresets;
import com.emma.bridge.camera.CameraTracker;
import com.google.gson.JsonObject;

/**
 * Handles "configure_camera" command — applies preset overrides and
 * lerp speed from Python's config.json.
 *
 * Only available in camera mode.
 *
 * Params: {
 *   "lerp_speed": 0.15,           (optional)
 *   "tp_distance": 20.0,          (optional, blocks — camera /tp threshold)
 *   "presets": {                   (optional)
 *     "front_face": { "forward": 6.0, "up": 1.0, "pitch": -5.0 },
 *     ...
 *   }
 * }
 *
 * Returns: { "presets_applied": N, "lerp_speed": F, "tp_distance": D }
 */
public class ConfigureCameraHandler implements ICommandHandler {

    private final CameraTracker tracker;

    public ConfigureCameraHandler(CameraTracker tracker) {
        this.tracker = tracker;
    }

    @Override
    public String getCommand() {
        return "configure_camera";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        int presetsApplied = 0;

        if (params.has("lerp_speed")) {
            float speed = params.get("lerp_speed").getAsFloat();
            tracker.setLerpSpeed(speed);
        }

        if (params.has("tp_distance")) {
            double dist = params.get("tp_distance").getAsDouble();
            tracker.setTpDistance(dist);
        }

        if (params.has("presets") && params.get("presets").isJsonObject()) {
            JsonObject presets = params.getAsJsonObject("presets");
            CameraPresets.applyOverrides(presets);
            presetsApplied = presets.size();
            // Force recompute so the active preset picks up new offsets
            tracker.forceRecompute();
        }

        JsonObject result = new JsonObject();
        result.addProperty("presets_applied", presetsApplied);
        result.addProperty("lerp_speed", tracker.getLerpSpeed());
        result.addProperty("tp_distance", tracker.getTpDistance());
        result.addProperty("available", String.join(", ", CameraPresets.names()));
        return result;
    }
}
