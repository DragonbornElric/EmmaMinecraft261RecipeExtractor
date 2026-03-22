package com.emma.bridge.goap;

import java.util.ArrayList;
import java.util.List;

/**
 * Registry of all available GOAP actions.
 *
 * Actions are registered at startup and remain for the session lifetime.
 * Each tick, the scorer calls getViableActions() to get actions whose
 * preconditions are met.
 */
public class ActionRegistry {

    private final List<GoapAction> actions = new ArrayList<>();

    /**
     * Register a new action.
     */
    public void register(GoapAction action) {
        actions.add(action);
    }

    /**
     * Get all registered actions.
     */
    public List<GoapAction> getAllActions() {
        return actions;
    }

    /**
     * Get actions whose preconditions are currently met.
     */
    public List<GoapAction> getViableActions(WorldState state) {
        List<GoapAction> viable = new ArrayList<>();
        for (GoapAction action : actions) {
            if (action.checkPreconditions(state)) {
                viable.add(action);
            }
        }
        return viable;
    }

    /**
     * Find an action by name.
     */
    public GoapAction getAction(String name) {
        for (GoapAction action : actions) {
            if (action.getName().equals(name)) return action;
        }
        return null;
    }

    public int size() {
        return actions.size();
    }
}
