package com.emma.endinv.network.payloads;

import com.emma.endinv.AbstractModInitializer;
import com.emma.endinv.client.gui.ScreenFramework;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.Optional;

public interface ModPacketPayload extends CustomPacketPayload {

    String id();

    default Type<? extends CustomPacketPayload> type(){
        return new Type<>(AbstractModInitializer.withModLocation(id()));
    }

    void handle(ModPacketContext context);

    static Optional<com.emma.endinv.client.gui.page.manager.PageManager> getClientPageMeta(){
        return Optional.ofNullable(ScreenFramework.getInstance());
    }
}
