package com.emma.endinv;

import com.emma.endinv.network.IPacketDistributor;
import com.emma.endinv.network.payloads.ModPacketPayload;
import com.emma.endinv.network.FabricClientNetworking;
import com.emma.endinv.network.FabricNetworking;
import net.minecraft.server.level.ServerPlayer;

public class FabricPacketDistributor implements IPacketDistributor {

    @Override
    public void sendToServer(ModPacketPayload payload) {
        FabricClientNetworking.sendToServer(payload);
    }

    @Override
    public void sendToPlayer(ServerPlayer player, ModPacketPayload payload) {
        FabricNetworking.sendToPlayer(player, payload);
    }

    @Override
    public void sendToAllPlayer(ModPacketPayload payload) {

    }
}
