package com.emma.endinv.network.payloads.toClient;

import com.emma.endinv.AbstractModInitializer;
import com.emma.endinv.client.CachedSrcInv;
import com.emma.endinv.client.gui.page.StarredItemPage;
import com.emma.endinv.network.payloads.ModPacketContext;
import com.emma.endinv.network.payloads.ModPacketPayload;
import com.emma.endinv.util.ItemStackLike;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

public record SetStarredPagePayload(List<ItemStackLike> stacks) implements ModPacketPayload {

    public static void encode(SetStarredPagePayload payload, FriendlyByteBuf o){
        o.writeCollection(payload.stacks, (buf, like) -> ItemStackLike.STREAM_CODEC.encode((net.minecraft.network.RegistryFriendlyByteBuf) buf, like));
    }

    public static SetStarredPagePayload decode(FriendlyByteBuf o){
        return new SetStarredPagePayload(o.readList(buf -> ItemStackLike.STREAM_CODEC.decode((net.minecraft.network.RegistryFriendlyByteBuf) buf)));
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, SetStarredPagePayload> STREAM_CODEC =
            StreamCodec.of((buf, value) -> encode(value, buf), SetStarredPagePayload::decode);

    public static final CustomPacketPayload.Type<SetStarredPagePayload> TYPE =
            new CustomPacketPayload.Type<>(AbstractModInitializer.withModLocation("starred_item"));

    @Override
    public String id() {
        return "starred_item";
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    @Override
    public void handle(ModPacketContext context) {
        CachedSrcInv.INSTANCE.affinities.syncStarredItems(stacks.stream().map(ItemStackLike::toKey).toList());
        ModPacketPayload.getClientPageMeta().ifPresent(mng->{
            if(mng.getDisplayingPage() instanceof StarredItemPage page){
                page.initializeAsMap(stacks);
            }
        });
    }
}
