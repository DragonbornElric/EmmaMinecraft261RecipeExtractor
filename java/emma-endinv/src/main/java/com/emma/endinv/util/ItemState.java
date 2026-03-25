package com.emma.endinv.util;

import com.emma.endinv.data.EndInvCodecStrategy;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.FriendlyByteBuf;

public record ItemState(int count, long lastModTime) {


    public static void encode(FriendlyByteBuf o,ItemState state){
        o.writeInt(state.count);
        o.writeLong(state.lastModTime);
    }

    public static ItemState decode(FriendlyByteBuf o){
        return new ItemState(o.readInt(),o.readLong());
    }

    public static final Codec<ItemState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf(EndInvCodecStrategy.ITEM_COUNT_KEY).forGetter(ItemState::count),
            Codec.LONG.fieldOf(EndInvCodecStrategy.LAST_MOD_TIME_LONG_KEY).forGetter(ItemState::lastModTime)
    ).apply(instance,ItemState::new));
}
