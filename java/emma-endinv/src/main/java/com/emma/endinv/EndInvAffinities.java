package com.emma.endinv;

import com.emma.endinv.data.EndInvCodecStrategy;
import com.emma.endinv.integrate.FluidData;
import com.emma.endinv.util.ItemKey;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

public class EndInvAffinities {

    public static final Logger LOGGER = LogUtils.getLogger();

    public final List<ItemKey> starredItems = new ArrayList<>();
    public final List<FluidData> fluids = new ArrayList<>();

    public static final Codec<EndInvAffinities> CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
                    Codec.list(ItemKey.CODEC).fieldOf(EndInvCodecStrategy.BOOKMARK_LIST_KEY).forGetter(a->a.starredItems)
            ).apply(instance, lst -> {
                var aff = new EndInvAffinities();
                lst.forEach(aff::addStarredItem);
                return aff;
            })
    );

    public EndInvAffinities(){}

    public void addStarredItem(ItemKey stack){
        for (ItemKey item : starredItems) {
            if (item.equals(stack)) {
                return;
            }
        }
        starredItems.add(stack);
    }

    public void addStarredItem(ItemStack stack){
        addStarredItem(ItemKey.asKey(stack));
    }

    public void removeStarredItem(ItemStack stack){
        if (stack.isEmpty()) return;
        removeStarredItem(ItemKey.asKey(stack));
    }

    public void removeStarredItem(ItemKey stack){
        starredItems.removeIf(ik -> ik.equals(stack));
    }

    public void syncStarredItems(List<ItemKey> stacks){//todo log
        starredItems.clear();
        starredItems.addAll(stacks);
    }

    /**
     * Get list of starred items of EndInv.
     * @param startIndex the startIndex of sublist
     * @param length the length of sublist, if too big, will return whole or just to last.
     * @return the sublist of starred items without copy.
     */
    public List<ItemKey> getStarredItems(int startIndex, int length){
        if(length >= starredItems.size()) return starredItems;
        if(startIndex+length > starredItems.size()) return starredItems.subList(startIndex,-1);
        return starredItems.subList(startIndex,startIndex+length);
    }
}
