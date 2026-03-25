package com.emma.endinv.util;

import com.emma.endinv.data.EndInvCodecStrategy;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 *
 * @param item
 * @param components in mc versions using component, those are not null.
 */
public record ItemKey(Item item,@NotNull DataComponentPatch components) {

    public static final StreamCodec<RegistryFriendlyByteBuf,ItemKey> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.registry(Registries.ITEM),ItemKey::item,
            DataComponentPatch.STREAM_CODEC,ItemKey::components,
            ItemKey::new
    );

    public static final Codec<ItemKey> CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
                    Item.CODEC.fieldOf(EndInvCodecStrategy.ITEM_ID_KEY).forGetter(ItemKey::itemHolder),
                    DataComponentPatch.CODEC.optionalFieldOf(EndInvCodecStrategy.COMPONENTS_KEY, DataComponentPatch.EMPTY).forGetter(ItemKey::components)
            ).apply(instance ,ItemKey::new)
    );

    public ItemKey(Holder<Item> itemHolder, DataComponentPatch components){
        this(itemHolder.value(),components);
    }

    public ItemKey(Item item, @Nullable DataComponentPatch components){
        this.item = item;
        this.components = components==null? DataComponentPatch.EMPTY : components;
    }

    public ItemStack toStack(int count){
        return new ItemStack(BuiltInRegistries.ITEM.wrapAsHolder(item),count,components);
    }

    private Holder<Item> itemHolder(){
        return item.builtInRegistryHolder();
    }

    public static ItemKey asKey(ItemStack stack){
        return new ItemKey(stack.getItem(),stack.getComponentsPatch());
    }
}
