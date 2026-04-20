package com.emma.bridge.goap;

import net.minecraft.client.Minecraft;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class UtilityScorerTest {

    @Test
    public void allZeroScoresReturnNoWinner() {
        GoalSet goalSet = new GoalSet();
        ActionRegistry registry = new ActionRegistry();
        FakeAction zeroA = new FakeAction("ZeroA", true, 0f);
        FakeAction zeroB = new FakeAction("ZeroB", true, 0f);
        registry.register(zeroA);
        registry.register(zeroB);

        UtilityScorer scorer = new UtilityScorer(registry, goalSet);
        WorldState state = new WorldState();
        state.health = 20f;
        state.maxHealth = 20f;
        state.hunger = 20;

        GoapAction winner = scorer.scoreAndSelect(state);

        assertNull(winner);
        assertEquals("none", scorer.getCurrentActiveAction());
        assertEquals("none", AgentDebugState.getInstance().activeAction);
    }

    @Test
    public void positiveScoreStillWinsAuction() {
        GoalSet goalSet = new GoalSet();
        ActionRegistry registry = new ActionRegistry();
        FakeAction zero = new FakeAction("Zero", true, 0f);
        FakeAction positive = new FakeAction("Positive", true, 2f);
        registry.register(zero);
        registry.register(positive);

        UtilityScorer scorer = new UtilityScorer(registry, goalSet);
        WorldState state = new WorldState();
        state.health = 20f;
        state.maxHealth = 20f;
        state.hunger = 20;

        GoapAction winner = scorer.scoreAndSelect(state);

        assertSame(positive, winner);
        assertEquals("Positive", scorer.getCurrentActiveAction());
    }

    private static final class FakeAction extends GoapAction {
        private final String name;
        private final boolean preconditions;
        private final float score;

        private FakeAction(String name, boolean preconditions, float score) {
            this.name = name;
            this.preconditions = preconditions;
            this.score = score;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean checkPreconditions(WorldState state) {
            return preconditions;
        }

        @Override
        public float computeScore(WorldState state, GoalSet goals) {
            return score;
        }

        @Override
        public void execute(Minecraft client) {
            // No-op for scorer tests.
        }
    }
}