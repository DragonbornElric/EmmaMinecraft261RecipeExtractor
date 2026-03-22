package com.emma.bridge.goap;

import com.google.gson.JsonObject;

/**
 * Singleton holding death data that persists across respawns.
 *
 * The player entity is recreated on respawn, so death data cannot live on
 * the player or on GoapAction instance fields that depend on player state.
 * This singleton survives the full session lifetime.
 *
 * Also handles death-loop detection via a ring buffer of recent deaths.
 */
public class DeathContext {

    private static final DeathContext INSTANCE = new DeathContext();

    // 15-minute real-time timeout for recovery attempts.
    //
    // IMPORTANT — Despawn timer context:
    // Minecraft's 5-minute item despawn timer ONLY ticks while the chunk is loaded.
    // On Emma's private server, the entire world is NOT kept loaded — chunks unload
    // when no player is nearby. When Emma dies and respawns elsewhere, the death
    // chunks unload and the despawn clock PAUSES. It resumes only when Emma (or
    // another player) re-enters render distance and reloads those chunks.
    //
    // This means the 15-minute timeout below is a REAL-TIME SANITY CAP on how long
    // Emma spends trying to recover, NOT a race against item despawn. Do not reduce
    // this timeout thinking items will despawn — they won't until the chunk reloads.
    // If you need to extend this timeout for long-distance recoveries, it is safe
    // to do so without risk of items disappearing.
    private static final long RECOVERY_TIMEOUT_MS = 15 * 60 * 1000;

    // Death-loop detection: 2+ deaths within this window AND within LOOP_DISTANCE
    private static final long LOOP_WINDOW_MS = 120_000;   // 2 minutes
    private static final double LOOP_DISTANCE = 30.0;     // blocks

    // Ring buffer for loop detection
    private static final int RING_SIZE = 5;
    private final long[] deathTimestamps = new long[RING_SIZE];
    private final double[][] deathPositions = new double[RING_SIZE][3];
    private int deathRingIndex = 0;
    private int totalDeaths = 0;

    // Latest death snapshot (null if none or recovery finished)
    private DeathSnapshot latestDeath = null;

    // Recovery state
    private boolean recoveryComplete = false;

    private DeathContext() {}

    public static DeathContext getInstance() {
        return INSTANCE;
    }

    // ── Death snapshot ──────────────────────────────────────────────

    /**
     * Immutable snapshot of a single death event.
     */
    public static class DeathSnapshot {
        public final double x, y, z;
        public final String dimension;
        public final String cause;
        public final int hostileCount;
        public final long timestampMs;
        public final boolean lavaRelated;
        public final boolean voidDeath;

        public DeathSnapshot(double x, double y, double z, String dimension,
                             String cause, int hostileCount) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.dimension = dimension;
            this.cause = cause;
            this.hostileCount = hostileCount;
            this.timestampMs = System.currentTimeMillis();

            // Classify death type from message and position
            String lowerCause = cause != null ? cause.toLowerCase() : "";
            this.lavaRelated = lowerCause.contains("lava")
                    || lowerCause.contains("burn")
                    || lowerCause.contains("fire");
            this.voidDeath = y < -60
                    || lowerCause.contains("void")
                    || lowerCause.contains("fell out of the world");
        }

        public JsonObject toJson() {
            JsonObject j = new JsonObject();
            j.addProperty("x", (int) x);
            j.addProperty("y", (int) y);
            j.addProperty("z", (int) z);
            j.addProperty("dimension", dimension);
            j.addProperty("cause", cause);
            j.addProperty("hostile_count", hostileCount);
            j.addProperty("elapsed_sec", (System.currentTimeMillis() - timestampMs) / 1000);
            j.addProperty("lava_related", lavaRelated);
            j.addProperty("void_death", voidDeath);
            return j;
        }
    }

    // ── Recording ───────────────────────────────────────────────────

    /**
     * Record a new death. Called by DamageListener when death_postmortem is built.
     */
    public void recordDeath(double x, double y, double z, String dimension,
                            String cause, int hostileCount) {
        latestDeath = new DeathSnapshot(x, y, z, dimension, cause, hostileCount);
        recoveryComplete = false;

        // Push to ring buffer for loop detection
        deathRingIndex = (deathRingIndex + 1) % RING_SIZE;
        deathTimestamps[deathRingIndex] = latestDeath.timestampMs;
        deathPositions[deathRingIndex][0] = x;
        deathPositions[deathRingIndex][1] = y;
        deathPositions[deathRingIndex][2] = z;
        totalDeaths++;
    }

    // ── Queries ─────────────────────────────────────────────────────

    public DeathSnapshot getLatestDeath() {
        return latestDeath;
    }

    public boolean isRecoveryComplete() {
        return recoveryComplete;
    }

    /**
     * Check if 15-minute real-time recovery window has elapsed.
     * See RECOVERY_TIMEOUT_MS comment for why this is safe to extend.
     */
    public boolean isTimedOut() {
        if (latestDeath == null) return true;
        return (System.currentTimeMillis() - latestDeath.timestampMs) > RECOVERY_TIMEOUT_MS;
    }

    /**
     * Detect death loop: 2+ deaths within 120 seconds within 30 blocks.
     * Prevents the classic "die → respawn → run back naked → die again" cycle.
     */
    public boolean isDeathLoop() {
        if (totalDeaths < 2) return false;

        int prev = (deathRingIndex - 1 + RING_SIZE) % RING_SIZE;
        long timeDiff = deathTimestamps[deathRingIndex] - deathTimestamps[prev];
        if (timeDiff > LOOP_WINDOW_MS) return false;

        double dx = deathPositions[deathRingIndex][0] - deathPositions[prev][0];
        double dy = deathPositions[deathRingIndex][1] - deathPositions[prev][1];
        double dz = deathPositions[deathRingIndex][2] - deathPositions[prev][2];
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        return dist < LOOP_DISTANCE;
    }

    // ── State transitions ───────────────────────────────────────────

    /**
     * Mark recovery as complete (items collected or giving up).
     * Clears the latest death so DeathRecoveryAction stops scoring.
     */
    public void markRecoveryComplete() {
        recoveryComplete = true;
        latestDeath = null;
    }

    /**
     * Debug output for agent_debug command.
     */
    public JsonObject toJson() {
        JsonObject j = new JsonObject();
        j.addProperty("total_deaths", totalDeaths);
        j.addProperty("death_loop", isDeathLoop());
        j.addProperty("timed_out", isTimedOut());
        j.addProperty("recovery_complete", recoveryComplete);
        if (latestDeath != null) {
            j.add("latest_death", latestDeath.toJson());
        }
        return j;
    }
}
