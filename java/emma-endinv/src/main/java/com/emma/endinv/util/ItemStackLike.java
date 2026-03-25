package com.emma.endinv.util;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;

public record ItemStackLike(Item item, int count,DataComponentPatch components) {

    public static final StreamCodec<RegistryFriendlyByteBuf,ItemStackLike> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.registry(Registries.ITEM),ItemStackLike::item,
            ByteBufCodecs.INT,ItemStackLike::count,
            DataComponentPatch.STREAM_CODEC,ItemStackLike::components,
            ItemStackLike::new
    );

    public static final Codec<ItemStackLike> CODEC = Codec.lazyInitialized(
            () -> RecordCodecBuilder.create(
                    p_381569_ -> p_381569_.group(
                                    Item.CODEC.fieldOf("id").forGetter(i -> i.item.builtInRegistryHolder()),
                                    Codec.INT.fieldOf("count").forGetter(ItemStackLike::count),
                                    DataComponentPatch.CODEC
                                            .optionalFieldOf("components", DataComponentPatch.EMPTY)
                                            .forGetter(ItemStackLike::components)
                            )
                            .apply(p_381569_, ItemStackLike::new)
            )
    );

    public static ItemStackLike asKey(ItemKey stack) {
        return new ItemStackLike(stack.item(), 0, stack.components());
    }

    public static ItemStackLike asKey(ItemKey stack, int count) {
        return new ItemStackLike(stack.item(), count, stack.components());
    }

    public ItemKey toKey() {
        return new ItemKey(item,components);
    }

    public ItemStackLike(Holder<Item> itemHolder, int count, DataComponentPatch components){
        this(itemHolder.value(),count,components);
    }
}
