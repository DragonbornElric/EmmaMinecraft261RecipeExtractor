package com.emma.endinv.network;

import com.emma.endinv.network.payloads.ModPacketPayload;
import net.minecraft.server.level.ServerPlayer;

public interface IPacketDistributor {

    void sendToServer(ModPacketPayload payload);

    void sendToPlayer(ServerPlayer player, ModPacketPayload payload);

    void sendToAllPlayer(ModPacketPayload payload);
}
