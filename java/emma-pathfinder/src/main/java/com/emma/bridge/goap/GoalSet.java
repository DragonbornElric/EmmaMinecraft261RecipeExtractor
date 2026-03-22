package com.emma.bridge.goap;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Active goal set for the GOAP planner.
 *
 * Contains hardcoded survival goals (always active) plus dynamic goals
 * set by Emma (Python/LLM) via the set_goals WebSocket command.
 *
 * Goals are scored each tick by the UtilityScorer. Higher priority = more
 * likely to drive action selection.
 */
public class GoalSet {

    private final List<Goal> goals = new ArrayList<>();
    private final List<Goal> derivedGoals = new ArrayList<>();

    public GoalSet() {
        // Hardcoded survival goals — always active
        goals.add(new Goal("survive", "survive", 8, null));
        goals.add(new Goal("stay_fed", "stay_fed", 6, null));
        goals.add(new Goal("be_lit", "be_lit", 5, null));
    }

    /**
     * Replace all dynamic (non-survival) goals. Called from SetGoalsHandler.
     * Also clears derived goals — they'll be recomputed by GoalDecomposer.
     */
    public void setDynamicGoals(List<Goal> newGoals) {
        goals.removeIf(g -> !g.isSurvival());
        goals.addAll(newGoals);
        derivedGoals.clear();
    }

    /**
     * Add a single dynamic goal. If a goal with the same ID exists, it is replaced.
     * Clears derived goals — they'll be recomputed by GoalDecomposer.
     */
    public void addDynamicGoal(Goal goal) {
        goals.removeIf(g -> !g.isSurvival() && g.id.equals(goal.id));
        goals.add(goal);
        derivedGoals.clear();
    }

    /**
     * Remove a dynamic goal by ID. Returns true if a goal was removed.
     * Clears derived goals — they'll be recomputed by GoalDecomposer.
     */
    public boolean removeDynamicGoal(String goalId) {
        boolean removed = goals.removeIf(g -> !g.isSurvival() && g.id.equals(goalId));
        if (removed) {
            derivedGoals.clear();
        }
        return removed;
    }

    /**
     * Get all dynamic (non-survival, non-derived) goal IDs.
     */
    public List<String> getDynamicGoalIds() {
        List<String> ids = new ArrayList<>();
        for (Goal g : goals) {
            if (!g.isSurvival()) ids.add(g.id);
        }
        return ids;
    }

    /**
     * Replace all derived (decomposed) goals. Called from GoalDecomposer.
     */
    public void setDerivedGoals(List<Goal> newDerived) {
        derivedGoals.clear();
        derivedGoals.addAll(newDerived);
    }

    /**
     * Get all active goals (user + survival + derived).
     * Actions score against this combined list.
     */
    public List<Goal> getGoals() {
        List<Goal> all = new ArrayList<>(goals.size() + derivedGoals.size());
        all.addAll(goals);
        all.addAll(derivedGoals);
        return all;
    }

    /**
     * Get only user + survival goals (no derived).
     */
    public List<Goal> getUserGoals() {
        return goals;
    }

    /**
     * Get only derived goals.
     */
    public List<Goal> getDerivedGoals() {
        return derivedGoals;
    }

    /**
     * Find a goal by ID (searches user + derived).
     */
    public Optional<Goal> getGoal(String id) {
        for (Goal g : goals) {
            if (g.id.equals(id)) return Optional.of(g);
        }
        for (Goal g : derivedGoals) {
            if (g.id.equals(id)) return Optional.of(g);
        }
        return Optional.empty();
    }

    /**
     * Get a goal's priority by ID, returning 0 if not found.
     */
    public float getGoalPriority(String id) {
        return getGoal(id).map(g -> g.priority).orElse(0f);
    }

    /**
     * Serialize all goals to JSON for debug output.
     */
    public JsonArray toJson() {
        JsonArray arr = new JsonArray();
        for (Goal g : goals) {
            arr.add(g.toJson());
        }
        for (Goal g : derivedGoals) {
            arr.add(g.toJson());
        }
        return arr;
    }

    // ── Goal definition ──────────────────────────────────────────

    public static class Goal {
        public final String id;
        public final String type;
        public final float priority;
        public final JsonObject target;  // type-specific (e.g., {item: "diamond", count: 3})
        public final String parentGoalId;  // non-null for derived goals

        // Survival goal IDs
        private static final List<String> SURVIVAL_IDS = List.of("survive", "stay_fed", "be_lit");

        public Goal(String id, String type, float priority, JsonObject target) {
            this(id, type, priority, target, null);
        }

        public Goal(String id, String type, float priority, JsonObject target, String parentGoalId) {
            this.id = id;
            this.type = type;
            this.priority = priority;
            this.target = target;
            this.parentGoalId = parentGoalId;
        }

        public boolean isSurvival() {
            return SURVIVAL_IDS.contains(id);
        }

        public boolean isDerived() {
            return parentGoalId != null;
        }

        public static boolean isSurvivalId(String id) {
            return SURVIVAL_IDS.contains(id);
        }

        public JsonObject toJson() {
            JsonObject j = new JsonObject();
            j.addProperty("id", id);
            j.addProperty("type", type);
            j.addProperty("priority", priority);
            j.addProperty("is_survival", isSurvival());
            j.addProperty("is_derived", isDerived());
            if (parentGoalId != null) j.addProperty("parent_goal", parentGoalId);
            if (target != null) j.add("target", target);
            return j;
        }
    }
}
