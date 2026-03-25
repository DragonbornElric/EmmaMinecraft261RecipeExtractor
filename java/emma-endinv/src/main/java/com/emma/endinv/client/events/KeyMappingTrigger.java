package com.emma.endinv.client.events;

import com.emma.endinv.AbstractClientModInitializer;
import com.emma.endinv.client.ClientModInfo;
import com.emma.endinv.client.KeyMappings;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.player.LocalPlayer;

public final class KeyMappingTrigger {

    private KeyMappingTrigger() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            LocalPlayer player = client.player;
            if (player == null) {
                return;
            }
            KeyMapping key;
            if (AbstractClientModInitializer.ENDINV_CLIENT != null) {
                key = AbstractClientModInitializer.ENDINV_CLIENT.KEY_MAPPING_MAP.get(KeyMappings.OPEN_MENU);
                while (key.consumeClick()) {
                    ClientModInfo.sendOpenMenu();
                }
            }
        });
    }
}
