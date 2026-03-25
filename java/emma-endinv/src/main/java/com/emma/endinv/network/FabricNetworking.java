package com.emma.endinv.network;

import com.emma.endinv.network.payloads.ModPacketContext;
import com.emma.endinv.network.payloads.ModPacketPayload;
import com.emma.endinv.network.payloads.SyncedConfig;
import com.emma.endinv.network.payloads.toClient.*;
import com.emma.endinv.network.payloads.toServer.*;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

public final class FabricNetworking {
    private FabricNetworking() {}

    public static void init() {
        // Register all payload types/codecs before registering receivers
        var c2s = PayloadTypeRegistry.serverboundPlay();
        c2s.register(ItemClickPayload.TYPE, ItemClickPayload.STREAM_CODEC);
        c2s.register(CreativeItemModPayload.TYPE, CreativeItemModPayload.STREAM_CODEC);
        c2s.register(ItemPageContext.TYPE, ItemPageContext.STREAM_CODEC);
        c2s.register(OpenEndInvPayload.TYPE, OpenEndInvPayload.STREAM_CODEC);
        c2s.register(QuickMoveToPagePayload.TYPE, QuickMoveToPagePayload.STREAM_CODEC);
        c2s.register(BulkQuickMoveFromPagePayload.TYPE, BulkQuickMoveFromPagePayload.STREAM_CODEC);
        c2s.register(StarItemPayload.TYPE, StarItemPayload.STREAM_CODEC);
        c2s.register(ToggleCraftingPayload.TYPE, ToggleCraftingPayload.STREAM_CODEC);
        c2s.register(SyncedConfig.TYPE, SyncedConfig.STREAM_CODEC);

        var s2c = PayloadTypeRegistry.clientboundPlay();
        s2c.register(EndInvContent.TYPE, EndInvContent.STREAM_CODEC);
        s2c.register(EndInvMetadata.TYPE, EndInvMetadata.STREAM_CODEC);
        s2c.register(ItemPickedUpPayload.TYPE, ItemPickedUpPayload.STREAM_CODEC);
        s2c.register(SetItemDisplayContentPayload.TYPE, SetItemDisplayContentPayload.STREAM_CODEC);
        s2c.register(SetStarredPagePayload.TYPE, SetStarredPagePayload.STREAM_CODEC);
        s2c.register(MenuAttachabilityPayload.TYPE, MenuAttachabilityPayload.STREAM_CODEC);
        s2c.register(SyncedConfig.TYPE, SyncedConfig.STREAM_CODEC);
    }

    public static void sendToPlayer(ServerPlayer player, ModPacketPayload payload) {
        ServerPlayNetworking.send(player, payload);
    }

    private static ModPacketContext context(net.minecraft.world.entity.player.Player player) { return () -> player; }
}
