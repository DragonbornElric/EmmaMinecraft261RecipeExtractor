package com.emma.bridge.goap;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

/**
 * Base class for all GOAP actions.
 *
 * Each action defines:
 * - Preconditions: what must be true for this action to be viable
 * - Score computation: how valuable is this action right now
 * - Per-goal relevance: for collateral scoring across all active goals
 * - Personality category: which personality weight applies
 * - Execution: what to do when selected
 *
 * Scoring flow (managed by UtilityScorer):
 *   1. action.computeScore(state, goals)  → primary score
 *   2. Σ action.relevanceToGoal(state, g) × g.priority for non-primary goals  → collateral
 *   3. primary × personality_bias  → dampened desire (bias = 1.0 + influence × (weight - 1.0))
 *   4. (biased_primary + collateral) × success_rate  → personality affects desire, not competence
 *   5. + HYSTERESIS_BONUS if active  → anti-thrash
 *
 * Zero EmmaClef dependencies — uses Minecraft directly.
 */
public abstract class GoapAction {

    /**
     * Human-readable name for debugging (e.g., "EatFood", "AttackEntity").
     */
    public abstract String getName();

    /**
     * Check if this action can be executed given current world state.
     * Must be fast — called for every action every tick.
     */
    public abstract boolean checkPreconditions(WorldState state);

    /**
     * Compute primary utility score for this action given current state and goals.
     * This is the base score before collateral, personality, and success_rate are applied.
     *
     * Typical implementation:
     *   Find the best-matching goal → goal.priority × relevance × proximity × urgency
     *
     * @return  Score >= 0. Return 0 if irrelevant.
     */
    public abstract float computeScore(WorldState state, GoalSet goals);

    /**
     * Execute this action. Called once when the action is first selected.
     * For ongoing actions (e.g., mining), the action continues until
     * a different action wins the next scoring round.
     *
     * @param client  Minecraft instance for game interaction
     */
    public abstract void execute(Minecraft client);

    // ── Collateral scoring support ────────────────────────────────

    /**
     * How relevant is this action to a specific goal?
     * Used by UtilityScorer for cross-goal collateral scoring.
     *
     * @return  1.0 = prerequisite, 0.5 = byproduct, 0.0 = no relation
     */
    public float relevanceToGoal(WorldState state, GoalSet.Goal goal) {
        return 0.0f;
    }

    /**
     * Which goal this action primarily serves (set during computeScore).
     * UtilityScorer excludes this goal from collateral calculation to avoid double-counting.
     *
     * @return  Goal ID, or null if collateral scoring not applicable
     */
    public String getPrimaryGoalId() {
        return null;
    }

    // ── Personality integration ───────────────────────────────────

    /**
     * Which personality category this action falls under.
     * UtilityScorer multiplies the score by the corresponding personality weight.
     *
     * Categories: "safety", "aggression", "exploration", "resource_hoarding", "neutral"
     *
     * @return  Category string. "neutral" = unaffected by personality weights (1.0×).
     */
    public String personalityCategory() {
        return "neutral";
    }

    // ── Strategy support ──────────────────────────────────────────

    /**
     * Optional: return this action's StrategyKnowledge for sub-strategy intelligence.
     * Only relevant for complex actions with sub-strategies (e.g., AttackEntityAction).
     */
    public StrategyKnowledge getStrategyKnowledge() {
        return null;
    }

    // ── Lifecycle ─────────────────────────────────────────────────

    /**
     * Called every tick while this action is the active action.
     * Override for ongoing behavior (e.g., re-fire eating if interrupted,
     * continue mining, check combat state).
     */
    public void tick(Minecraft client) {
        // Default: no per-tick behavior
    }

    /**
     * Called when this action is no longer the active action.
     * Override to clean up (e.g., cancel Emmatone pathing).
     */
    public void onDeactivated(Minecraft client) {
        // Default: no cleanup needed
    }

    /**
     * Whether this action is still running / has work to do.
     * If false, the action is considered "done" and score drops.
     */
    public boolean isActive() {
        return true;
    }

    /**
     * Minimum ticks this action must remain active before the scorer can switch away.
     * Override for commitment actions (eating = 32 ticks, attack cooldown = 13 ticks).
     * During this window, the scorer will not switch to another action unless a
     * reflex fires (reflexes always preempt).
     *
     * @return  0 = no lock (default), >0 = locked for N ticks after execute()
     */
    public int getMinimumActiveTicks() {
        return 0;
    }

    // ── Debug ─────────────────────────────────────────────────────

    /**
     * Detailed score breakdown for debug display.
     * Override to provide action-specific scoring details.
     */
    public JsonObject getScoreBreakdown(WorldState state, GoalSet goals) {
        JsonObject breakdown = new JsonObject();
        breakdown.addProperty("action", getName());
        breakdown.addProperty("score", computeScore(state, goals));
        breakdown.addProperty("preconditions_met", checkPreconditions(state));
        breakdown.addProperty("personality_category", personalityCategory());
        String primaryGoal = getPrimaryGoalId();
        if (primaryGoal != null) {
            breakdown.addProperty("primary_goal", primaryGoal);
        }
        return breakdown;
    }
}
