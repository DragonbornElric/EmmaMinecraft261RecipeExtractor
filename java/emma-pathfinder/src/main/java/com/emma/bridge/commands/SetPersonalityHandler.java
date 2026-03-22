package com.emma.bridge.commands;

import com.emma.bridge.goap.UtilityScorer;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;

/**
 * WebSocket command: set_personality
 *
 * Sets GOAP personality weights from Python/Emma.
 * Weights are dampened via lerp-to-neutral before application:
 *   effective_bias = 1.0 + INFLUENCE × (raw_weight - 1.0)
 * This means personality nudges desire without suppressing actions entirely.
 *
 * Params:
 *   {"safety": 1.2, "aggression": 0.8, "exploration": 0.5, "resource_hoarding": 0.8}
 *
 * Values: 0.0–2.0 (1.0 = neutral). At default influence 0.4:
 *   0.0 → 0.6× primary score (reduced, not eliminated)
 *   1.0 → 1.0× primary score (neutral)
 *   2.0 → 1.4× primary score (boosted)
 *
 * Categories:
 *   safety          — biases flee/shield/eat actions
 *   aggression      — biases melee/attack actions
 *   exploration     — biases navigate-to-unknown
 *   resource_hoarding — biases mining/gathering/storing
 */
public class SetPersonalityHandler implements ICommandHandler {

    private final UtilityScorer scorer;

    public SetPersonalityHandler(UtilityScorer scorer) {
        this.scorer = scorer;
    }

    @Override
    public String getCommand() {
        return "set_personality";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        JsonObject result = new JsonObject();

        if (params == null) {
            result.addProperty("status", "error");
            result.addProperty("error", "Missing personality weights");
            return result;
        }

        Map<String, Float> weights = new HashMap<>();
        String[] categories = {"safety", "aggression", "exploration", "resource_hoarding"};

        for (String category : categories) {
            if (params.has(category)) {
                float val = params.get(category).getAsFloat();
                // Clamp to 0.0–2.0 range (allow slight boost above 1.0)
                weights.put(category, Math.max(0.0f, Math.min(2.0f, val)));
            }
        }

        if (weights.isEmpty()) {
            result.addProperty("status", "error");
            result.addProperty("error", "No valid personality weights provided. Use: safety, aggression, exploration, resource_hoarding");
            return result;
        }

        // Merge with existing (partial updates allowed)
        Map<String, Float> current = scorer.getPersonalityWeights();
        current.putAll(weights);
        scorer.setPersonalityWeights(current);

        result.addProperty("status", "ok");
        result.addProperty("weights_set", weights.size());
        JsonObject active = new JsonObject();
        for (var entry : scorer.getPersonalityWeights().entrySet()) {
            active.addProperty(entry.getKey(), entry.getValue());
        }
        result.add("active_weights", active);
        return result;
    }
}
