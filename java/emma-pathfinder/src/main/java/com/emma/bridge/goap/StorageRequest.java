package com.emma.bridge.goap;

import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.UUID;

/**
 * A request for the GOAP StoreItemsAction to deposit or withdraw items.
 *
 * Bridge commands (StorageHandler) produce requests; the GOAP action consumes them.
 * At most one pending request at a time — subsequent requests replace the previous one.
 */
public class StorageRequest {

    public enum Type { DEPOSIT, WITHDRAW }

    public final Type type;
    /** Target container position, or null for deposit_nearby (find nearest). */
    public final BlockPos pos;
    /** Items to transfer: {itemId → count}. */
    public final Map<String, Integer> items;
    /** Unique task ID for event correlation. */
    public final String taskId;

    public StorageRequest(Type type, BlockPos pos, Map<String, Integer> items) {
        this.type = type;
        this.pos = pos;
        this.items = Map.copyOf(items);
        this.taskId = UUID.randomUUID().toString().substring(0, 8);
    }

    // ── Static singleton queue ───────────────────────────────────

    private static StorageRequest pending = null;

    public static synchronized void submit(StorageRequest req) {
        pending = req;
    }

    public static synchronized StorageRequest peek() {
        return pending;
    }

    public static synchronized StorageRequest poll() {
        StorageRequest r = pending;
        pending = null;
        return r;
    }

    public static synchronized boolean hasPending() {
        return pending != null;
    }
}
