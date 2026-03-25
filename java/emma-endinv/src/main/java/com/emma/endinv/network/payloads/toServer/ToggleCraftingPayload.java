package com.emma.endinv.network.payloads.toServer;

import com.emma.endinv.AbstractModInitializer;
import com.emma.endinv.menu.EndlessInventoryMenu;
import com.emma.endinv.network.payloads.ModPacketContext;
import com.emma.endinv.network.payloads.ModPacketPayload;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Player;

public record ToggleCraftingPayload(boolean visible) implements ModPacketPayload {

    public static final StreamCodec<RegistryFriendlyByteBuf, ToggleCraftingPayload> STREAM_CODEC =
            StreamCodec.of((buf, value) -> encode(value, buf), ToggleCraftingPayload::decode);

    public static final CustomPacketPayload.Type<ToggleCraftingPayload> TYPE =
            new CustomPacketPayload.Type<>(AbstractModInitializer.withModLocation("toggle_crafting"));


    public static void encode(ToggleCraftingPayload payload, FriendlyByteBuf buf) {
        buf.writeBoolean(payload.visible);
    }

    public static ToggleCraftingPayload decode(FriendlyByteBuf buf) {
        return new ToggleCraftingPayload(buf.readBoolean());
    }

    @Override
    public String id() {
        return "toggle_crafting";
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    @Override
    public void handle(ModPacketContext context) {
        Player player = context.player();
        if (player == null) {
            return;
        }
        if (player.containerMenu instanceof EndlessInventoryMenu menu) {
            menu.setCraftingVisible(visible);
            menu.broadcastChanges();
        }
    }
}



