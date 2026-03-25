package com.emma.endinv.network;

import com.emma.endinv.network.payloads.ModPacketContext;
import com.emma.endinv.network.payloads.SyncedConfig;
import com.emma.endinv.network.payloads.toServer.*;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

public final class FabricServerNetworking {

    private FabricServerNetworking() {}

    public static void init() {
        ServerPlayNetworking.registerGlobalReceiver(ItemClickPayload.TYPE, (payload, ctx) -> ctx.server().execute(() -> payload.handle(context(ctx.player()))));
        ServerPlayNetworking.registerGlobalReceiver(CreativeItemModPayload.TYPE, (payload, ctx) -> ctx.server().execute(() -> payload.handle(context(ctx.player()))));
        ServerPlayNetworking.registerGlobalReceiver(ItemPageContext.TYPE, (payload, ctx) -> ctx.server().execute(() -> payload.handle(context(ctx.player()))));
        ServerPlayNetworking.registerGlobalReceiver(OpenEndInvPayload.TYPE, (payload, ctx) -> ctx.server().execute(() -> {
            payload.handle(context(ctx.player()));
        }));
        ServerPlayNetworking.registerGlobalReceiver(QuickMoveToPagePayload.TYPE, (payload, ctx) -> ctx.server().execute(() -> payload.handle(context(ctx.player()))));
        ServerPlayNetworking.registerGlobalReceiver(StarItemPayload.TYPE, (payload, ctx) -> ctx.server().execute(() -> payload.handle(context(ctx.player()))));
        ServerPlayNetworking.registerGlobalReceiver(ToggleCraftingPayload.TYPE, (payload, ctx) -> ctx.server().execute(() -> payload.handle(context(ctx.player()))));
        ServerPlayNetworking.registerGlobalReceiver(SyncedConfig.TYPE, (payload, ctx) -> ctx.server().execute(() -> payload.handle(context(ctx.player()))));
        ServerPlayNetworking.registerGlobalReceiver(BulkQuickMoveFromPagePayload.TYPE, (payload, ctx) -> ctx.server().execute(() -> payload.handle(context(ctx.player()))));
    }


    private static ModPacketContext context(ServerPlayer player) {
        return () -> player;
    }
}
