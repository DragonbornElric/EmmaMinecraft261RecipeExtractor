package com.emma.overflow;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common initializer — runs on both client and server.
 * Registers the C2S/S2C payload types so both sides know the packet format.
 */
public class OverflowMod implements ModInitializer {

    public static final String MOD_ID = "emma-overflow";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        OverflowPayloads.registerAll();
        LOGGER.info("[EmmaOverflow] Payload types registered");
    }
}
