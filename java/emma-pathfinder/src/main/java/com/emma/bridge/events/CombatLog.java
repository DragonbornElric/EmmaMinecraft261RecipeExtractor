package com.emma.bridge.events;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Fixed-size ring buffer that records combat events with no cooldown.
 * O(1) writes, zero GC pressure. Holds the last 64 events (~16-32 seconds
 * of active combat at 2-4 events/sec).
 *
 * Singleton — accessed via CombatLog.getInstance().
 *
 * Thread safety: all access is from the client tick thread. No synchronization needed.
 */
public class CombatLog {

    private static final CombatLog INSTANCE = new CombatLog();
    private static final int CAPACITY = 64;

    private final JsonObject[] entries = new JsonObject[CAPACITY];
    private int head = 0;   // next write position
    private int count = 0;  // entries currently stored

    private CombatLog() {}

    public static CombatLog getInstance() {
        return INSTANCE;
    }

    /**
     * Record a combat event. No cooldown — the ring buffer handles volume.
     *
     * @param type  Event type (e.g., "damage_taken", "damage_dealt", "defense_decision")
     * @param data  Event-specific data fields
     */
    public void record(String type, JsonObject data) {
        JsonObject entry = new JsonObject();
        entry.addProperty("type", type);
        entry.addProperty("timestamp_ms", System.currentTimeMillis());
        entry.add("data", data);

        entries[head] = entry;
        head = (head + 1) % CAPACITY;
        if (count < CAPACITY) count++;
    }

    /**
     * Return all entries oldest → newest as a JsonArray.
     */
    public JsonArray snapshot() {
        return snapshotLast(count);
    }

    /**
     * Return the last N entries oldest → newest.
     */
    public JsonArray snapshotLast(int n) {
        if (n > count) n = count;
        JsonArray result = new JsonArray();

        // Start position: head points at next write, so oldest of the last N
        // is at (head - n) mod CAPACITY
        int start = ((head - n) % CAPACITY + CAPACITY) % CAPACITY;
        for (int i = 0; i < n; i++) {
            int idx = (start + i) % CAPACITY;
            if (entries[idx] != null) {
                result.add(entries[idx]);
            }
        }
        return result;
    }

    /**
     * Clear all entries (e.g., on respawn).
     */
    public void clear() {
        for (int i = 0; i < CAPACITY; i++) {
            entries[i] = null;
        }
        head = 0;
        count = 0;
    }

    /**
     * Number of entries currently stored.
     */
    public int size() {
        return count;
    }
}
