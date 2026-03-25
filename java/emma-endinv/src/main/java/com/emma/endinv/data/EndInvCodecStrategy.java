package com.emma.endinv.data;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;


public interface EndInvCodecStrategy {

    Logger LOGGER = LogUtils.getLogger();

    String ITEM_ID_KEY = "id";
    String ITEM_COUNT_KEY = "count";
    String DEPRECATED_COUNT_KEY = "Count";
    String COMPONENTS_KEY = "components";

    String END_INV_LIST_KEY = "endless_inventories";
    String ITEM_LIST_KEY = "Items";
    String SIZE_INT_KEY = "Size";
    String LAST_MOD_TIME_LONG_KEY = "modState";
    String UUID_KEY = "uuid";
    String MAX_STACK_SIZE_INT_KEY = "maxItemStackSize";
    String INFINITY_BOOL_KEY = "Infinity";
    String AFFINITY_KEY = "Affinities";
    String BOOKMARK_LIST_KEY = "starred_items";
    String OWNER_UUID_KEY = "Owner";
    String WHITE_LIST_KEY = "white_list";
    String ACCESSIBILITY_KEY = "Accessibility";


    Codec<ItemStack> ITEM_STACK_CODEC = Codec.lazyInitialized(
            () -> RecordCodecBuilder.create(
                    p_381569_ -> p_381569_.group(
                                    Item.CODEC.fieldOf("id").forGetter(ItemStack::typeHolder),
                                    Codec.INT.fieldOf("count").orElse(1).forGetter(ItemStack::getCount),
                                    DataComponentPatch.CODEC
                                            .optionalFieldOf("components", DataComponentPatch.EMPTY)
                                            .forGetter(ItemStack::getComponentsPatch)
                            )
                            .apply(p_381569_, ItemStack::new)
            )
    );
}
