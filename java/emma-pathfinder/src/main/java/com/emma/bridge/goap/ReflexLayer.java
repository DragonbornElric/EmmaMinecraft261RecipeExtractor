package com.emma.bridge.goap;

import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds a list of GoapReflex instances and ticks them before GOAP scoring.
 */
public class ReflexLayer {

    private final List<GoapReflex> reflexes = new ArrayList<>();

    public void register(GoapReflex reflex) {
        reflexes.add(reflex);
    }

    /**
     * Tick all reflexes. Call before GOAP scoring each tick.
     * Activates reflexes whose conditions are met, releases those whose conditions cleared.
     */
    public void tick(WorldState state, Minecraft client) {
        for (GoapReflex reflex : reflexes) {
            boolean shouldFire = reflex.shouldFire(state, client);

            if (shouldFire) {
                if (!reflex.isActive()) {
                    reflex.setActive(true);
                }
                reflex.fire(client);  // fire every tick while condition holds
            } else if (reflex.isActive()) {
                reflex.release(client);
                reflex.setActive(false);
            }
        }
    }

    /** Returns true if any active reflex suppresses scoring. */
    public boolean isScoringSupressed() {
        for (GoapReflex reflex : reflexes) {
            if (reflex.isActive() && reflex.suppressesScoring()) {
                return true;
            }
        }
        return false;
    }

    /** List of currently firing reflexes (for debug). */
    public List<GoapReflex> getActiveReflexes() {
        List<GoapReflex> active = new ArrayList<>();
        for (GoapReflex reflex : reflexes) {
            if (reflex.isActive()) {
                active.add(reflex);
            }
        }
        return active;
    }
}
