package com.emma.bridge.goap;

import net.minecraft.client.Minecraft;

/**
 * Abstract base class for reflexes that run outside the GOAP scorer.
 * Reflexes need sub-tick reaction time (shield blocking, force field, MLG bucket).
 */
public abstract class GoapReflex {

    private boolean active = false;

    /** Human-readable name for debug. */
    public abstract String getName();

    /** Check trigger condition. */
    public abstract boolean shouldFire(WorldState state, Minecraft client);

    /** Execute the reflex. */
    public abstract void fire(Minecraft client);

    /** Cleanup when condition clears. */
    public abstract void release(Minecraft client);

    /**
     * Override to true for reflexes that need exclusive control (shield, MLG).
     * When true, GOAP scoring is suppressed while this reflex is active.
     */
    public boolean suppressesScoring() {
        return false;
    }

    /** Whether this reflex is currently firing. */
    public boolean isActive() {
        return active;
    }

    void setActive(boolean active) {
        this.active = active;
    }
}
