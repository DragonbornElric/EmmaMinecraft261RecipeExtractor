package com.emma.overflow;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Defines the C2S and S2C packet payloads for the overflow system.
 *
 * Both payloads carry a requestId (for correlating request/response) and a
 * JSON string payload. Using JSON keeps the codec trivial while allowing
 * flexible action-specific data.
 */
public final class OverflowPayloads {

    private OverflowPayloads() {}

    // ── C2S: client asks the server to do something ────────────────────

    public record Request(String requestId, String jsonPayload) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<Request> ID =
                new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("emma", "overflow_req"));

        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, Request::requestId,
                        ByteBufCodecs.STRING_UTF8, Request::jsonPayload,
                        Request::new
                );

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    // ── S2C: server sends the result back ──────────────────────────────

    public record Response(String requestId, String jsonPayload) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<Response> ID =
                new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("emma", "overflow_resp"));

        public static final StreamCodec<RegistryFriendlyByteBuf, Response> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, Response::requestId,
                        ByteBufCodecs.STRING_UTF8, Response::jsonPayload,
                        Response::new
                );

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    // ── Registration (called from common ModInitializer) ───────────────

    public static void registerAll() {
        PayloadTypeRegistry.serverboundPlay().register(Request.ID, Request.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Response.ID, Response.CODEC);
    }
}
