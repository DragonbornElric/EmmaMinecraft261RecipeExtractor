package com.emma.bridge.goap;

import com.google.gson.JsonObject;
import net.minecraft.world.entity.Entity;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Structured outcome report from an action or sub-strategy.
 *
 * "Failure produces intelligence, not just a boolean."
 *
 * When MeleeRush fails (0 hits, 3 taken, obstruction found), the report
 * feeds into StrategyKnowledge. DigAndAttack reads the obstruction position
 * and scores higher. Intelligence flows between strategies.
 */
public class StrategyReport {

    /** Whether the action/strategy completed its objective. */
    public boolean complete;

    /** Whether the action/strategy can still make progress. */
    public boolean viable = true;

    /** Progress rate (0.0 = stuck, 1.0 = on track). */
    public float progressRate;

    /** Block positions that are obstructing progress. */
    public final List<BlockPos> obstructions = new ArrayList<>();

    /** Threats discovered during execution. */
    public final List<Entity> discoveredThreats = new ArrayList<>();

    /** Arbitrary key-value findings (e.g., "hits_landed" → 0, "arrows_used" → 3). */
    public final Map<String, Object> findings = new HashMap<>();

    /**
     * Serialize to JSON for debug output.
     */
    public JsonObject toJson() {
        JsonObject j = new JsonObject();
        j.addProperty("complete", complete);
        j.addProperty("viable", viable);
        j.addProperty("progress_rate", progressRate);
        j.addProperty("obstruction_count", obstructions.size());
        j.addProperty("discovered_threat_count", discoveredThreats.size());

        if (!findings.isEmpty()) {
            JsonObject f = new JsonObject();
            for (var entry : findings.entrySet()) {
                Object val = entry.getValue();
                if (val instanceof Number n) {
                    f.addProperty(entry.getKey(), n);
                } else if (val instanceof Boolean b) {
                    f.addProperty(entry.getKey(), b);
                } else {
                    f.addProperty(entry.getKey(), String.valueOf(val));
                }
            }
            j.add("findings", f);
        }

        return j;
    }
}
