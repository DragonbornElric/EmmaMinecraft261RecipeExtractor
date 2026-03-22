package com.emma.bridge.goap;

/**
 * Shared boolean state between GOAP reflexes and actions.
 * Replaces EmmaClef chain accessor cross-references
 * (getFoodChain().isTryingToEat(), getMLGBucketChain().isFalling(), etc.).
 *
 * Single-threaded (game tick) — no synchronization needed.
 */
public final class GoapStateFlags {

    private static final GoapStateFlags INSTANCE = new GoapStateFlags();

    /** Set by EatFoodAction when eating. */
    public boolean isEating;

    /** Set by MLGBucketReflex when fall detected. */
    public boolean isFalling;

    /** Set by MLGBucketReflex during water place/pickup. */
    public boolean isMLGActive;

    /** Set by ShieldBlockReflex when shield is held up. */
    public boolean isShielding;

    /** Set by MLGBucketReflex during chorus fruit failsafe. */
    public boolean isChorusFruiting;

    /** Set by ForceFieldReflex when swinging at mobs. */
    public boolean isForceFieldActive;

    private GoapStateFlags() {}

    public static GoapStateFlags get() {
        return INSTANCE;
    }

    /** Reset all flags to false. */
    public void reset() {
        isEating = false;
        isFalling = false;
        isMLGActive = false;
        isShielding = false;
        isChorusFruiting = false;
        isForceFieldActive = false;
    }
}
