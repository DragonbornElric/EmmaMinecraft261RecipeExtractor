package com.emma.bridge.mixin;

import com.emma.bridge.events.StatsTracker;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundAwardStatsPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures Mojang statistics responses from the server.
 * When the client requests stats via REQUEST_STATS and the server responds
 * with ClientboundAwardStatsPacket, this mixin forwards the data to StatsTracker
 * for processing and WebSocket broadcast to the overlay.
 */
@Mixin(ClientPacketListener.class)
public abstract class StatisticsListenerMixin {

    @Inject(method = "handleAwardStats", at = @At("TAIL"))
    private void emma$onStatisticsReceived(ClientboundAwardStatsPacket packet, CallbackInfo ci) {
        StatsTracker.getInstance().onStatsReceived(packet.stats());
    }
}
