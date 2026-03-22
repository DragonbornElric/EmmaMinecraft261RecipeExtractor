package com.emma.bridge.goap;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Map;

/**
 * Shared intelligence across sub-strategies within a parent action.
 *
 * MeleeRush discovers an obstruction → DigAndAttack reads it immediately.
 * Failure in one strategy produces intelligence that other strategies consume.
 *
 * Each parent action (e.g., AttackEntityAction) owns one StrategyKnowledge.
 * The global UtilityScorer also holds one for action-level success rates.
 */
public class StrategyKnowledge {

    /** Known obstructions: position → type description. */
    public final Map<BlockPos, String> obstructions = new HashMap<>();

    /** Per-strategy success rates: strategy name → rate (0.0–1.0). Starts at 1.0. */
    public final Map<String, Float> successRates = new HashMap<>();

    /** Blocks confirmed unreachable: position → tick when marked. */
    public final Map<BlockPos, Long> unreachableBlocks = new HashMap<>();

    /** Last report from each strategy. */
    public final Map<String, StrategyReport> lastReports = new HashMap<>();

    /**
     * Get the success rate for a strategy/action. Returns 1.0 if unknown.
     */
    public float getSuccessRate(String name) {
        return successRates.getOrDefault(name, 1.0f);
    }

    /**
     * Record a strategy's outcome report. Updates success rates and transfers
     * obstruction/threat intelligence.
     */
    public void recordReport(String strategyName, StrategyReport report) {
        lastReports.put(strategyName, report);

        if (!report.viable) {
            // Strategy failed — crash success rate
            float current = successRates.getOrDefault(strategyName, 1.0f);
            successRates.put(strategyName, Math.max(0.05f, current * 0.5f));
        } else if (report.complete) {
            // Strategy succeeded — recover success rate
            float current = successRates.getOrDefault(strategyName, 1.0f);
            successRates.put(strategyName, Math.min(1.0f, current + 0.1f));
        }

        // Transfer obstruction intelligence
        for (BlockPos pos : report.obstructions) {
            obstructions.put(pos, "from_" + strategyName);
        }
    }

    /**
     * Clear expired unreachable blocks (older than maxAge ticks).
     */
    public void clearExpired(long currentTick, long maxAge) {
        unreachableBlocks.entrySet().removeIf(e -> currentTick - e.getValue() > maxAge);
    }

    /**
     * Reset all knowledge (e.g., on respawn or major state change).
     */
    public void reset() {
        obstructions.clear();
        successRates.clear();
        unreachableBlocks.clear();
        lastReports.clear();
    }

    /**
     * Serialize to JSON for debug output.
     */
    public JsonObject toJson() {
        JsonObject j = new JsonObject();
        j.addProperty("obstruction_count", obstructions.size());
        j.addProperty("unreachable_count", unreachableBlocks.size());

        JsonObject rates = new JsonObject();
        for (var entry : successRates.entrySet()) {
            rates.addProperty(entry.getKey(), entry.getValue());
        }
        j.add("success_rates", rates);

        return j;
    }
}
