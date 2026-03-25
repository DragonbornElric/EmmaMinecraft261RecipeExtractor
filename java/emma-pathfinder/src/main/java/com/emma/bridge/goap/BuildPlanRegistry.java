package com.emma.bridge.goap;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Holds the active build plan for BuildStructureAction.
 *
 * Python sends a build goal via set_build_goal, which stores the plan here.
 * BuildStructureAction reads the plan when it's time to start building.
 * GoalDecomposer reads materials from the goal target JSON (not from here).
 */
public class BuildPlanRegistry {

    private BuildPlan activePlan;

    public static class BuildPlan {
        public final String buildId;
        public final String name;
        public final int originX, originY, originZ;
        public final JsonArray blocks;
        public final Map<String, Integer> materials;
        public final float priority;

        public BuildPlan(String buildId, String name, int originX, int originY, int originZ,
                         JsonArray blocks, Map<String, Integer> materials, float priority) {
            this.buildId = buildId;
            this.name = name;
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
            this.blocks = blocks;
            this.materials = materials;
            this.priority = priority;
        }
    }

    public synchronized BuildPlan getActivePlan() {
        return activePlan;
    }

    public synchronized void setActivePlan(BuildPlan plan) {
        this.activePlan = plan;
    }

    public synchronized void clearActivePlan() {
        this.activePlan = null;
    }

    public synchronized boolean hasActivePlan() {
        return activePlan != null;
    }

    /**
     * Parse materials from a JSON object: {"minecraft:oak_planks": 64, ...}
     */
    public static Map<String, Integer> parseMaterials(JsonObject materialsJson) {
        Map<String, Integer> materials = new LinkedHashMap<>();
        for (var entry : materialsJson.entrySet()) {
            materials.put(entry.getKey(), entry.getValue().getAsInt());
        }
        return materials;
    }
}
