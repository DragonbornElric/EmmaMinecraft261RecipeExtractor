package com.emma.bridge;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Emma Bridge Mod — Main initializer (runs on both client and server).
 *
 * This mod provides a WebSocket bridge between the Emma AI orchestrator
 * (Python) and Minecraft + Emmatone. All actual logic lives in the
 * client-side initializer ({@link EmmaBridgeClient}) since we only
 * control the local player's client.
 */
public class EmmaBridgeMod implements ModInitializer {

    public static final String MOD_ID = "emma-bridge";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        String version = FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        LOGGER.info("[Emma Bridge] Common initializer loaded (v{})", version);
    }
}
