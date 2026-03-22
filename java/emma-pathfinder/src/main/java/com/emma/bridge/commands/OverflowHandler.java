package com.emma.bridge.commands;

import com.emma.bridge.EmmaBridgeMod;
import com.emma.overflow.OverflowClientMod.OverflowClientApi;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Bridge command handler for the overflow inventory system.
 *
 * Translates WebSocket commands from Python into C2S packets sent to the
 * server-side overflow mod, awaits the S2C response asynchronously, and
 * returns the result via a CompletableFuture.
 *
 * IMPORTANT: This handler implements IAsyncCommandHandler to avoid deadlocking
 * the tick thread. Fabric delivers S2C responses on the main client thread —
 * if we block that thread with future.get(), the response handler never fires.
 *
 * Commands:
 *   overflow trash       — permanently destroy items from inventory
 *   overflow deposit     — move items from inventory to virtual overflow
 *   overflow withdraw    — retrieve items from overflow to inventory
 *   overflow status      — report overflow contents
 *   overflow clear_junk  — auto-trash all junk items (client sends junk list)
 *   overflow free_slots  — proactively free N inventory slots
 */
public class OverflowHandler implements IAsyncCommandHandler {

    /** Timeout for server responses (ms). Used by the blocking fallback only. */
    private static final long RESPONSE_TIMEOUT_MS = 5000;

    @Override
    public String getCommand() {
        return "overflow";
    }

    /**
     * Non-blocking async execution. Sends C2S packet on the tick thread
     * and returns a future that resolves when the S2C response arrives.
     * MessageHandler uses this path to avoid deadlocking the tick thread.
     */
    @Override
    public CompletableFuture<JsonObject> executeAsync(JsonObject params) {
        if (!isOverflowAvailable()) {
            JsonObject err = new JsonObject();
            err.addProperty("success", false);
            err.addProperty("error", "emma-overflow mod not loaded (install on both server and client)");
            return CompletableFuture.completedFuture(err);
        }

        String action = params.has("action") ? params.get("action").getAsString() : "";
        JsonObject request = buildServerRequest(action, params);

        if ("clear_junk".equals(action) || "free_slots".equals(action)) {
            if (!request.has("junk_items")) {
                request.add("junk_items", getThrowawayItemList());
            }
        }

        // Send C2S packet (on tick thread — correct) and return future WITHOUT blocking.
        // OverflowClientApi.sendRequest() already has a 5-second auto-timeout on the future.
        try {
            CompletableFuture<JsonObject> future = OverflowClientApi.sendRequest(request);
            return future.exceptionally(ex -> {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                EmmaBridgeMod.LOGGER.warn("[Overflow] Async error for action {}: {}", action, cause.getMessage());
                JsonObject err = new JsonObject();
                err.addProperty("success", false);
                if (cause instanceof TimeoutException) {
                    err.addProperty("error", "Server response timeout");
                } else {
                    err.addProperty("error", cause.getMessage());
                }
                return err;
            });
        } catch (Exception e) {
            EmmaBridgeMod.LOGGER.warn("[Overflow] Failed to send request: {}", e.getMessage());
            JsonObject err = new JsonObject();
            err.addProperty("success", false);
            err.addProperty("error", e.getMessage());
            return CompletableFuture.completedFuture(err);
        }
    }

    /**
     * Blocking fallback — only used if called outside the async dispatch path.
     * WARNING: Will deadlock if called on the tick thread.
     */
    @Override
    public JsonObject execute(JsonObject params) {
        try {
            return executeAsync(params).get(RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("success", false);
            err.addProperty("error", e.getMessage());
            return err;
        }
    }

    /**
     * Build the server request JSON from the WebSocket params.
     * The server expects: {action, items?, target?, junk_items?}
     */
    private JsonObject buildServerRequest(String action, JsonObject params) {
        JsonObject request = new JsonObject();
        request.addProperty("action", action);

        if (params.has("items")) {
            request.add("items", params.get("items"));
        }
        if (params.has("target")) {
            request.addProperty("target", params.get("target").getAsInt());
        }
        if (params.has("junk_items")) {
            request.add("junk_items", params.get("junk_items"));
        }
        if (params.has("protected_items")) {
            request.add("protected_items", params.get("protected_items"));
        }

        return request;
    }

    /**
     * Get the throwaway item list.
     * TODO: Rebuild junk item configuration without EmmaClef settings.
     * Currently returns an empty list — Python side should provide junk_items in params.
     */
    private JsonArray getThrowawayItemList() {
        JsonArray junkList = new JsonArray();
        for (String id : DEFAULT_JUNK) {
            junkList.add(id);
        }
        return junkList;
    }

    /** Check if the overflow mod is loaded (soft dependency). */
    private boolean isOverflowAvailable() {
        try {
            return OverflowClientApi.isAvailable();
        } catch (NoClassDefFoundError e) {
            return false;
        }
    }

    /** Default junk items. Empty — Python side should provide junk_items in params. */
    private static final String[] DEFAULT_JUNK = {};
}
