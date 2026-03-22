package com.emma.bridge.goap;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Singleton holding the output of each tick's GOAP scoring.
 * Populated as a side-effect of UtilityScorer — zero extra computation.
 * Read by AgentDebugHandler when polled via WebSocket.
 *
 * All access is from the client tick thread — no synchronization needed.
 */
public class AgentDebugState {

    private static final AgentDebugState INSTANCE = new AgentDebugState();
    private static final int SWITCH_HISTORY_CAPACITY = 20;

    public static AgentDebugState getInstance() {
        return INSTANCE;
    }

    // ── Tick-by-tick scoring data ────────────────────────────────

    /** All actions with their scores from the last tick. */
    public final List<ScoredAction> lastAuction = new ArrayList<>();

    /** The winning action name. */
    public String activeAction = "none";

    /** Hysteresis bonus applied to the active action. */
    public float hysteresisBonus = 1.5f;

    /** All goals with their computed scores from the last tick. */
    public final List<ScoredGoal> goalScores = new ArrayList<>();

    /** Level state from the last tick. */
    public WorldState lastWorldState;

    /** Diff between this tick's world state and the previous tick. */
    public JsonObject worldStateDiff;

    /** Current tick number. */
    public long tick;

    // ── Stall detection debug ─────────────────────────────────────

    /** Ticks since last meaningful progress. */
    public int noProgressTicks = 0;

    /** Per-goal stall penalties (copy from UtilityScorer each tick). */
    public final Map<String, Float> goalStallPenalties = new HashMap<>();

    // ── Switch history (ring buffer) ─────────────────────────────

    private final ActionSwitchEvent[] switchHistory = new ActionSwitchEvent[SWITCH_HISTORY_CAPACITY];
    private int switchHead = 0;
    private int switchCount = 0;

    /**
     * Record an action switch.
     */
    public void recordSwitch(String fromAction, String toAction, float fromScore, float toScore, String reason) {
        ActionSwitchEvent event = new ActionSwitchEvent(
                System.currentTimeMillis(), tick, fromAction, toAction, fromScore, toScore, reason);
        switchHistory[switchHead] = event;
        switchHead = (switchHead + 1) % SWITCH_HISTORY_CAPACITY;
        if (switchCount < SWITCH_HISTORY_CAPACITY) switchCount++;
    }

    /**
     * Get recent switch history, oldest first.
     */
    public List<ActionSwitchEvent> getSwitchHistory() {
        List<ActionSwitchEvent> result = new ArrayList<>();
        int start = ((switchHead - switchCount) % SWITCH_HISTORY_CAPACITY + SWITCH_HISTORY_CAPACITY) % SWITCH_HISTORY_CAPACITY;
        for (int i = 0; i < switchCount; i++) {
            int idx = (start + i) % SWITCH_HISTORY_CAPACITY;
            if (switchHistory[idx] != null) result.add(switchHistory[idx]);
        }
        return result;
    }

    /**
     * Serialize full debug state to JSON.
     */
    public JsonObject toJson(boolean includeHistory) {
        JsonObject j = new JsonObject();
        j.addProperty("tick", tick);
        j.addProperty("active_action", activeAction);
        j.addProperty("hysteresis_bonus", hysteresisBonus);

        // Auction
        JsonArray auction = new JsonArray();
        for (ScoredAction sa : lastAuction) {
            auction.add(sa.toJson());
        }
        j.add("auction", auction);

        // Goals
        JsonArray goals = new JsonArray();
        for (ScoredGoal sg : goalScores) {
            goals.add(sg.toJson());
        }
        j.add("goals", goals);

        // Stall detection
        j.addProperty("no_progress_ticks", noProgressTicks);
        if (!goalStallPenalties.isEmpty()) {
            JsonObject stallObj = new JsonObject();
            for (Map.Entry<String, Float> entry : goalStallPenalties.entrySet()) {
                stallObj.addProperty(entry.getKey(), entry.getValue());
            }
            j.add("goal_stall_penalties", stallObj);
        }

        // Level state diff
        if (worldStateDiff != null) {
            j.add("world_state_diff", worldStateDiff);
        }

        // Switch history
        if (includeHistory) {
            JsonArray history = new JsonArray();
            for (ActionSwitchEvent e : getSwitchHistory()) {
                history.add(e.toJson());
            }
            j.add("switch_history", history);
        }

        return j;
    }

    // ── Inner types ──────────────────────────────────────────────

    public static class ScoredAction {
        public final String name;
        public final float score;
        public final float rawScore;  // before hysteresis
        public final boolean isActive;
        public final JsonObject breakdown;

        public ScoredAction(String name, float score, float rawScore, boolean isActive, JsonObject breakdown) {
            this.name = name;
            this.score = score;
            this.rawScore = rawScore;
            this.isActive = isActive;
            this.breakdown = breakdown;
        }

        public JsonObject toJson() {
            JsonObject j = new JsonObject();
            j.addProperty("action", name);
            j.addProperty("score", score);
            j.addProperty("raw_score", rawScore);
            j.addProperty("is_active", isActive);
            if (breakdown != null) j.add("breakdown", breakdown);
            return j;
        }
    }

    public static class ScoredGoal {
        public final String id;
        public final String type;
        public final float priority;
        public final float score;
        public final boolean isDerived;
        public final String parentGoalId;

        public ScoredGoal(String id, String type, float priority, float score) {
            this(id, type, priority, score, false, null);
        }

        public ScoredGoal(String id, String type, float priority, float score, boolean isDerived, String parentGoalId) {
            this.id = id;
            this.type = type;
            this.priority = priority;
            this.score = score;
            this.isDerived = isDerived;
            this.parentGoalId = parentGoalId;
        }

        public JsonObject toJson() {
            JsonObject j = new JsonObject();
            j.addProperty("id", id);
            j.addProperty("type", type);
            j.addProperty("priority", priority);
            j.addProperty("score", score);
            if (isDerived) {
                j.addProperty("is_derived", true);
                if (parentGoalId != null) j.addProperty("parent_goal", parentGoalId);
            }
            return j;
        }
    }

    public static class ActionSwitchEvent {
        public final long timestampMs;
        public final long tick;
        public final String fromAction;
        public final String toAction;
        public final float fromScore;
        public final float toScore;
        public final String reason;

        public ActionSwitchEvent(long timestampMs, long tick, String fromAction, String toAction,
                                 float fromScore, float toScore, String reason) {
            this.timestampMs = timestampMs;
            this.tick = tick;
            this.fromAction = fromAction;
            this.toAction = toAction;
            this.fromScore = fromScore;
            this.toScore = toScore;
            this.reason = reason;
        }

        public JsonObject toJson() {
            JsonObject j = new JsonObject();
            j.addProperty("timestamp_ms", timestampMs);
            j.addProperty("tick", tick);
            j.addProperty("from", fromAction);
            j.addProperty("to", toAction);
            j.addProperty("from_score", fromScore);
            j.addProperty("to_score", toScore);
            j.addProperty("reason", reason);
            return j;
        }
    }
}
