package com.emma.bridge.commands;

import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;

/**
 * Extension of ICommandHandler for commands that involve asynchronous
 * round-trips (e.g. C2S packet → wait for S2C response).
 *
 * The tick thread calls {@link #executeAsync} which performs tick-thread work
 * (e.g. sending C2S packets) and returns a future immediately — without
 * blocking. MessageHandler attaches a callback to send the WebSocket response
 * when the future completes.
 *
 * This avoids the Emmatone-style deadlock where blocking the tick thread
 * prevents Fabric's S2C handler from resolving the future.
 */
public interface IAsyncCommandHandler extends ICommandHandler {

    /**
     * Begin asynchronous execution on the tick thread.
     *
     * The implementation MUST:
     * 1. Perform any tick-thread-required work (e.g. send C2S packets)
     * 2. Return a CompletableFuture that resolves when the response arrives
     *
     * The implementation MUST NOT call future.get() or otherwise block.
     *
     * @param params  The "params" object from the incoming JSON command
     * @return future that resolves to the JSON result
     */
    CompletableFuture<JsonObject> executeAsync(JsonObject params);
}
