package com.emma.bridge.goap;

import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Core GOAP scoring engine. Each tick:
 * 1. Get viable actions from ActionRegistry
 * 2. Score each action with dampened personality model:
 *    total = (primary × personality_bias + collateral) × success_rate
 * 3. Apply hysteresis bonus to the currently active action
 * 4. Winner = highest score
 * 5. Populate AgentDebugState as a side-effect
 *
 * Full scoring formula:
 *   primary_score = action.computeScore(state, goals)
 *     (internally: goal_priority × relevance_to_goal × proximity × urgency)
 *
 *   collateral = Σ (goal.priority × action.relevanceToGoal(state, goal))
 *     for all goals except the action's primary goal
 *
 *   personality_bias = 1.0 + INFLUENCE[category] × (raw_weight - 1.0)
 *     Dampened lerp-to-neutral: personality nudges desire, never suppresses it.
 *     Raw weight 0.0→bias 0.6, 1.0→1.0, 2.0→1.4 (at default influence 0.4).
 *     Applied only to primary score — collateral and success_rate are unaffected.
 *
 *   success_rate = globalKnowledge.getSuccessRate(action.getName())
 *     (starts at 1.0, decreases on failure)
 *
 *   total = (primary × personality_bias + collateral) × success_rate
 *   if active: total += HYSTERESIS_BONUS
 */
public class UtilityScorer {

    /** Bonus applied to the current action to prevent thrashing. */
    private static final float HYSTERESIS_BONUS = 1.5f;

    /** Minimum score difference to switch actions (additional anti-thrash). */
    private static final float SWITCH_THRESHOLD = 0.5f;

    /**
     * Per-category influence constants — how strongly each personality trait
     * affects scoring. All default to 0.4 (personality is a moderate nudge).
     * Independently tunable: e.g., safety could be bumped to 0.5 so survival
     * instinct has more pull than resource hoarding preference.
     *
     * At influence 0.4, effective multiplier range is 0.6×–1.4× (for raw 0.0–2.0).
     */
    private static final Map<String, Float> PERSONALITY_INFLUENCE = Map.of(
            "safety",            0.4f,
            "aggression",        0.4f,
            "exploration",       0.4f,
            "resource_hoarding", 0.4f
    );

    private final ActionRegistry registry;
    private final GoalSet goalSet;

    /** Global strategy knowledge for action-level success rates. */
    private final StrategyKnowledge globalKnowledge = new StrategyKnowledge();

    /**
     * Personality weights — live control surface set by Emma per stream.
     * Keys: "safety", "aggression", "exploration", "resource_hoarding"
     * Values: 0.0–2.0 (1.0 = neutral). Dampened via PERSONALITY_INFLUENCE
     * before application — personality nudges desire, never suppresses it.
     */
    private final Map<String, Float> personalityWeights = new HashMap<>();

    private String currentActiveAction = "none";
    private float currentActiveScore = 0;

    // ── Per-goal stall detection ──────────────────────────────────
    /** Per-goal stall penalties: goalId → accumulated penalty. */
    private final Map<String, Float> goalStallPenalties = new HashMap<>();

    /** Ticks since the active action made meaningful progress (moved or gained items). */
    private int noProgressTicks = 0;
    private double lastProgressX, lastProgressY, lastProgressZ;
    private int lastProgressInvHash = 0;

    /** Grace period before stall decay kicks in (3 seconds). */
    private static final int STALL_GRACE_TICKS = 60;

    /** Penalty increase per tick while stalling (~0.2/second). */
    private static final float STALL_INCREASE_PER_TICK = 0.01f;

    /** Penalty recovery per tick for all goals (~0.08/second). */
    private static final float STALL_RECOVERY_PER_TICK = 0.004f;

    public UtilityScorer(ActionRegistry registry, GoalSet goalSet) {
        this.registry = registry;
        this.goalSet = goalSet;

        // Default personality (1.0 = neutral baseline):
        // safety slightly above neutral — Emma prefers to survive
        // aggression slightly below — not bloodthirsty by default
        // exploration well below — cautious explorer
        // resource_hoarding slightly below — moderate gatherer
        personalityWeights.put("safety", 1.2f);
        personalityWeights.put("aggression", 0.8f);
        personalityWeights.put("exploration", 0.5f);
        personalityWeights.put("resource_hoarding", 0.8f);
    }

    /**
     * Score all actions and return the winner.
     * Populates AgentDebugState as a side-effect.
     *
     * @param state  Current world state
     * @return  The winning GoapAction, or null if no viable actions
     */
    public GoapAction scoreAndSelect(WorldState state) {
        AgentDebugState debug = AgentDebugState.getInstance();
        debug.lastAuction.clear();
        debug.goalScores.clear();
        debug.tick = state.tick;
        debug.hysteresisBonus = HYSTERESIS_BONUS;

        // Score goals (for debug display)
        for (GoalSet.Goal goal : goalSet.getGoals()) {
            float goalScore = computeGoalRelevance(goal, state);
            debug.goalScores.add(new AgentDebugState.ScoredGoal(
                    goal.id, goal.type, goal.priority, goalScore, goal.isDerived(), goal.parentGoalId));
        }

        // Clear expired unreachable blocks
        globalKnowledge.clearExpired(state.tick, 600); // 30 seconds

        // Expose unreachable blocks to actions via WorldState
        state.unreachableBlocks = globalKnowledge.unreachableBlocks;

        // Stall detection: check if active action is making progress
        double dx = state.posX - lastProgressX;
        double dy = state.posY - lastProgressY;
        double dz = state.posZ - lastProgressZ;
        double distMoved = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int currentInvHash = state.playerInventory.hashCode();

        if (distMoved > 1.0 || currentInvHash != lastProgressInvHash) {
            lastProgressX = state.posX;
            lastProgressY = state.posY;
            lastProgressZ = state.posZ;
            lastProgressInvHash = currentInvHash;
            noProgressTicks = 0;
        } else {
            noProgressTicks++;
        }

        // Increase penalty for the active action's primary goal if stalling
        if (noProgressTicks > STALL_GRACE_TICKS && !"none".equals(currentActiveAction)) {
            GoapAction active = registry.getAction(currentActiveAction);
            if (active != null) {
                String primaryGoal = active.getPrimaryGoalId();
                if (primaryGoal != null) {
                    goalStallPenalties.merge(primaryGoal, STALL_INCREASE_PER_TICK, Float::sum);
                }
            }
        }

        // Recover all stall penalties slowly
        goalStallPenalties.replaceAll((k, v) -> Math.max(0, v - STALL_RECOVERY_PER_TICK));
        goalStallPenalties.values().removeIf(v -> v <= 0);

        // Populate debug with stall info
        debug.noProgressTicks = noProgressTicks;
        debug.goalStallPenalties.clear();
        debug.goalStallPenalties.putAll(goalStallPenalties);

        // Score all actions
        List<GoapAction> viable = registry.getViableActions(state);
        GoapAction winner = null;
        float bestScore = Float.NEGATIVE_INFINITY;
        // Track the current active action's ACTUAL score this tick (not stale)
        float activeActionScoreThisTick = 0;

        for (GoapAction action : viable) {
            // 1. Primary score (action's own scoring logic)
            float primaryScore = action.computeScore(state, goalSet);

            // 2. Collateral scoring — cross-goal benefit
            float collateral = computeCollateral(action, state);

            // 3. Personality bias (dampened, applied to primary only)
            float personalityBias = getPersonalityMultiplier(action);
            float biasedPrimary = primaryScore * personalityBias;

            // 4. Success rate from global knowledge
            float successRate = globalKnowledge.getSuccessRate(action.getName());

            // Combine: (primary × personality_bias + collateral) × success_rate
            // Personality affects desire (primary), not competence (success) or side benefits (collateral)
            float rawScore = (biasedPrimary + collateral) * successRate;

            // 5. Hysteresis: bonus to current action (only if it still has real score)
            // Don't let hysteresis keep a dead action alive — if raw score is 0,
            // the action has no goal relevance and should yield.
            float finalScore = rawScore;
            boolean isActive = action.getName().equals(currentActiveAction);
            if (isActive) {
                if (rawScore > 0) {
                    finalScore += HYSTERESIS_BONUS;
                }
            }

            // 6. Per-goal stall penalty: if this action's primary goal is stalled, penalize
            String primaryGoal = action.getPrimaryGoalId();
            float goalPenalty = 0;
            if (primaryGoal != null) {
                goalPenalty = goalStallPenalties.getOrDefault(primaryGoal, 0f);
                finalScore -= goalPenalty;
            }

            // Capture active action's ACTUAL score after all adjustments
            if (isActive) {
                activeActionScoreThisTick = finalScore;
            }

            // Build debug breakdown — full decomposition for tuning
            JsonObject breakdown = action.getScoreBreakdown(state, goalSet);
            breakdown.addProperty("primary", primaryScore);
            breakdown.addProperty("personality_bias", personalityBias);
            breakdown.addProperty("primary_after_bias", biasedPrimary);
            breakdown.addProperty("collateral", collateral);
            breakdown.addProperty("success_rate", successRate);
            breakdown.addProperty("raw_combined", rawScore);
            if (goalPenalty > 0) {
                breakdown.addProperty("goal_stall_penalty", goalPenalty);
            }

            debug.lastAuction.add(new AgentDebugState.ScoredAction(
                    action.getName(), finalScore, rawScore, isActive, breakdown));

            if (finalScore > bestScore) {
                bestScore = finalScore;
                winner = action;
            }
        }

        // Also add non-viable actions to debug (with score 0)
        for (GoapAction action : registry.getAllActions()) {
            if (!viable.contains(action)) {
                debug.lastAuction.add(new AgentDebugState.ScoredAction(
                        action.getName(), 0, 0, false, null));
            }
        }

        // Sort auction by score descending for clean debug display
        debug.lastAuction.sort((a, b) -> Float.compare(b.score, a.score));

        // Determine if we should switch
        if (winner != null) {
            String winnerName = winner.getName();
            if (!winnerName.equals(currentActiveAction)) {
                // Compare against ACTUAL score this tick, not stale peak score.
                // Old bug: currentActiveScore never decreased, locking in peak values forever.
                if (bestScore - activeActionScoreThisTick > SWITCH_THRESHOLD || currentActiveAction.equals("none")) {
                    debug.recordSwitch(currentActiveAction, winnerName,
                            activeActionScoreThisTick, bestScore, "score_win");
                    currentActiveAction = winnerName;
                    currentActiveScore = bestScore;
                    // Reset stall tracking — new action gets a fresh grace period
                    noProgressTicks = 0;
                    lastProgressX = state.posX;
                    lastProgressY = state.posY;
                    lastProgressZ = state.posZ;
                    lastProgressInvHash = state.playerInventory.hashCode();
                } else {
                    // Stay with current action — margin too small
                    GoapAction current = registry.getAction(currentActiveAction);
                    if (current != null && current.checkPreconditions(state)) {
                        winner = current;
                        currentActiveScore = activeActionScoreThisTick;
                    } else {
                        // Current action is dead (preconditions failed) — allow switch
                        debug.recordSwitch(currentActiveAction, winnerName,
                                activeActionScoreThisTick, bestScore, "precondition_fail");
                        currentActiveAction = winnerName;
                        currentActiveScore = bestScore;
                        noProgressTicks = 0;
                        lastProgressX = state.posX;
                        lastProgressY = state.posY;
                        lastProgressZ = state.posZ;
                        lastProgressInvHash = state.playerInventory.hashCode();
                    }
                }
            } else {
                // Winner IS the current action — update with actual score
                currentActiveScore = bestScore;
            }
            debug.activeAction = currentActiveAction;
        }

        return winner;
    }

    /**
     * Compute collateral score: cross-goal benefit for non-primary goals.
     *
     * Example: Mining iron when you need a diamond pickaxe — iron is a prerequisite
     * for the pickaxe goal, so it gets collateral bonus from the diamond goal.
     */
    private float computeCollateral(GoapAction action, WorldState state) {
        String primaryGoalId = action.getPrimaryGoalId();
        if (primaryGoalId == null) return 0;  // action doesn't support collateral

        float collateral = 0;
        for (GoalSet.Goal goal : goalSet.getGoals()) {
            if (goal.id.equals(primaryGoalId)) continue;  // skip primary (already in raw score)
            float relevance = action.relevanceToGoal(state, goal);
            if (relevance > 0) {
                collateral += goal.priority * relevance;
            }
        }
        return collateral;
    }

    /**
     * Get dampened personality bias for an action based on its category.
     * Uses lerp-to-neutral: bias = 1.0 + influence × (raw_weight - 1.0)
     *
     * "neutral" category returns 1.0 (unaffected by personality).
     * At default influence 0.4: raw 0.0→0.6, raw 1.0→1.0, raw 2.0→1.4
     */
    private float getPersonalityMultiplier(GoapAction action) {
        String category = action.personalityCategory();
        if ("neutral".equals(category)) return 1.0f;
        float rawWeight = personalityWeights.getOrDefault(category, 1.0f);
        float influence = PERSONALITY_INFLUENCE.getOrDefault(category, 0.4f);
        return 1.0f + influence * (rawWeight - 1.0f);
    }

    /**
     * Compute how relevant a goal is given the current world state.
     * Used for debug display — the actual scoring is done per-action.
     */
    private float computeGoalRelevance(GoalSet.Goal goal, WorldState state) {
        return switch (goal.id) {
            case "survive" -> {
                float healthUrgency = 1.0f - (state.health / state.maxHealth);
                float threatFactor = state.threats.isEmpty() ? 0 :
                        1.0f / (1.0f + state.threats.get(0).distance / 5.0f);
                yield goal.priority * Math.max(healthUrgency, threatFactor);
            }
            case "stay_fed" -> {
                float hungerUrgency = 1.0f - (state.hunger / 20.0f);
                yield goal.priority * hungerUrgency;
            }
            case "be_lit" -> {
                float darkFactor = state.lightLevel < 7 ? 1.0f : 0.0f;
                yield goal.priority * darkFactor;
            }
            default -> goal.priority * 0.5f;
        };
    }

    // ── Personality management ─────────────────────────────────────

    /**
     * Set personality weights. Called from SetPersonalityHandler.
     */
    public void setPersonalityWeights(Map<String, Float> weights) {
        personalityWeights.clear();
        personalityWeights.putAll(weights);
    }

    /**
     * Get current personality weights (defensive copy).
     */
    public Map<String, Float> getPersonalityWeights() {
        return new HashMap<>(personalityWeights);
    }

    // ── Strategy knowledge ────────────────────────────────────────

    /**
     * Get global strategy knowledge (for action-level success rate tracking).
     */
    public StrategyKnowledge getGlobalKnowledge() {
        return globalKnowledge;
    }

    // ── Control ───────────────────────────────────────────────────

    /**
     * Reset the active action (e.g., on task cancel or respawn).
     */
    public void reset() {
        currentActiveAction = "none";
        currentActiveScore = 0;
        globalKnowledge.reset();
        goalStallPenalties.clear();
        noProgressTicks = 0;
    }

    public String getCurrentActiveAction() {
        return currentActiveAction;
    }
}
