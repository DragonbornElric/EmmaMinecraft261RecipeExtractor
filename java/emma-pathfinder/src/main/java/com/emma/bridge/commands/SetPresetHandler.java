package com.emma.bridge.commands;

import com.emma.bridge.EmmaBridgeMod;
import com.emma.bridge.camera.CameraPresets;
import com.emma.bridge.camera.CameraTracker;
import com.google.gson.JsonObject;

/**
 * Handles "set_camera_preset" command — switches the active camera preset.
 * Only available in camera mode.
 *
 * Params: { "preset": "overhead" }
 * Returns: { "preset": "<name>", "available": ["overhead","build_closeup",...] }
 */
public class SetPresetHandler implements ICommandHandler {

    private final CameraTracker tracker;

    public SetPresetHandler(CameraTracker tracker) {
        this.tracker = tracker;
    }

    @Override
    public String getCommand() {
        return "set_camera_preset";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        String presetName = params.has("preset") ? params.get("preset").getAsString() : null;

        if (presetName == null || presetName.isEmpty()) {
            throw new RuntimeException("Missing required parameter: preset");
        }

        if (CameraPresets.get(presetName) == null) {
            throw new RuntimeException("Unknown preset: " + presetName
                    + ". Available: " + CameraPresets.names());
        }

        tracker.setPreset(presetName);

        JsonObject result = new JsonObject();
        result.addProperty("preset", presetName);
        result.addProperty("available", String.join(", ", CameraPresets.names()));
        return result;
    }
}
