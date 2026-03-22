package com.emma.bridge.commands;

import com.emma.bridge.goap.ActionRegistry;
import com.emma.bridge.goap.actions.PlaceTorchAction;
import com.google.gson.JsonObject;

/**
 * Handles "torch" command — controls automatic torch placement via the
 * GOAP PlaceTorchAction.
 *
 * Params: { "action": "enable"|"disable"|"toggle"|"set_threshold"|"status",
 *           "threshold": int (only for set_threshold) }
 * Returns: { "enabled", "active", "threshold", "torch_count" }
 */
public class TorchHandler implements ICommandHandler {

    private final ActionRegistry actionRegistry;

    public TorchHandler(ActionRegistry actionRegistry) {
        this.actionRegistry = actionRegistry;
    }

    @Override
    public String getCommand() {
        return "torch";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        PlaceTorchAction torchAction = (PlaceTorchAction) actionRegistry.getAction("PlaceTorch");
        if (torchAction == null) {
            throw new IllegalStateException("PlaceTorchAction not registered in GOAP ActionRegistry");
        }

        String action = params.has("action") ? params.get("action").getAsString() : "status";

        switch (action) {
            case "enable" -> torchAction.setEnabled(true);
            case "disable" -> torchAction.setEnabled(false);
            case "toggle" -> torchAction.setEnabled(!torchAction.isEnabled());
            case "set_threshold" -> {
                if (!params.has("threshold")) {
                    throw new IllegalArgumentException("torch set_threshold: 'threshold' param required");
                }
                torchAction.setDarkThreshold(params.get("threshold").getAsInt());
            }
            case "status" -> {} // just return state below
            default -> throw new IllegalArgumentException(
                    "Unknown torch action: '" + action + "'. Valid: enable, disable, toggle, set_threshold, status");
        }

        JsonObject result = new JsonObject();
        result.addProperty("enabled", torchAction.isEnabled());
        result.addProperty("active", torchAction.isActive());
        result.addProperty("threshold", torchAction.getDarkThreshold());
        result.addProperty("torch_count", torchAction.getTorchCount());
        return result;
    }
}
