package com.emma.overflow;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Client-side entrypoint for the overflow system.
 *
 * Receives S2C response packets and resolves pending futures.
 * The bridge mod's OverflowHandler uses {@link OverflowClientApi} to send
 * requests and await responses.
 */
public class OverflowClientMod implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Register S2C response handler
        ClientPlayNetworking.registerGlobalReceiver(
                OverflowPayloads.Response.ID,
                (payload, context) -> {
                    String requestId = payload.requestId();
                    String jsonStr = payload.jsonPayload();
                    OverflowMod.LOGGER.info("[EmmaOverflow] S2C response received for {} on thread {}",
                            requestId, Thread.currentThread().getName());
                    OverflowClientApi.resolveRequest(requestId, jsonStr);
                }
        );

        OverflowClientApi.markAvailable();
        OverflowMod.LOGGER.info("[EmmaOverflow] Client-side initialized");
    }

    /**
     * Public API for the bridge mod (and other client-side code) to interact
     * with the server-side overflow system via packets.
     */
    public static final class OverflowClientApi {

        private static volatile boolean available = false;

        /** Pending request futures, keyed by requestId. */
        private static final Map<String, CompletableFuture<JsonObject>> pending =
                new ConcurrentHashMap<>();

        /** Last known overflow status (cached from most recent status response). */
        private static volatile JsonObject cachedStatus = null;

        private OverflowClientApi() {}

        static void markAvailable() {
            available = true;
        }

        /**
         * Returns true if the overflow mod is loaded and packet handlers are registered.
         */
        public static boolean isAvailable() {
            return available;
        }

        /**
         * Send a request to the server and return a future for the response.
         *
         * @param action  the overflow action (trash, deposit, withdraw, status, etc.)
         * @param request the full JSON request body (must include "action" key)
         * @return future that resolves when the server responds
         */
        public static CompletableFuture<JsonObject> sendRequest(JsonObject request) {
            String requestId = UUID.randomUUID().toString();
            CompletableFuture<JsonObject> future = new CompletableFuture<>();

            pending.put(requestId, future);

            // Auto-timeout after 5 seconds to prevent leaks
            future.orTimeout(5, TimeUnit.SECONDS).whenComplete((result, ex) -> {
                pending.remove(requestId);
                if (ex != null && result == null) {
                    OverflowMod.LOGGER.warn("[EmmaOverflow] Request {} timed out", requestId);
                }
            });

            // Send C2S packet
            OverflowMod.LOGGER.debug("[EmmaOverflow] Sending C2S request {} action={}",
                    requestId, request.has("action") ? request.get("action").getAsString() : "?");
            ClientPlayNetworking.send(
                    new OverflowPayloads.Request(requestId, request.toString())
            );

            return future;
        }

        /**
         * Convenience: send a simple action with no extra data.
         */
        public static CompletableFuture<JsonObject> sendAction(String action) {
            JsonObject req = new JsonObject();
            req.addProperty("action", action);
            return sendRequest(req);
        }

        /**
         * Called by the S2C handler to resolve a pending request.
         */
        static void resolveRequest(String requestId, String jsonStr) {
            CompletableFuture<JsonObject> future = pending.remove(requestId);
            if (future != null) {
                try {
                    JsonObject response = JsonParser.parseString(jsonStr).getAsJsonObject();

                    // Cache status responses
                    if (response.has("capacity")) {
                        cachedStatus = response;
                    }

                    future.complete(response);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            } else {
                OverflowMod.LOGGER.debug("[EmmaOverflow] Received response for unknown request: {}", requestId);
            }
        }

        /**
         * Returns the last cached overflow status, or null if never queried.
         */
        public static JsonObject getCachedStatus() {
            return cachedStatus;
        }
    }
}
