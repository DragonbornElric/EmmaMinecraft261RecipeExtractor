package com.emma.endinv;


import com.emma.endinv.util.Accessibility;
import com.emma.endinv.util.ItemKey;
import com.emma.endinv.util.ItemState;
import com.emma.endinv.util.SortType;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import org.jetbrains.annotations.Nullable;
import java.util.*;

import static com.emma.endinv.data.EndInvCodecStrategy.*;

/**An item container may have endless storage.*/
public class EndlessInventory extends SourceInventory {//todo add content transfer mode as a field

    private static final Logger LOGGER = LogUtils.getLogger();
    @SuppressWarnings("deprecation")
    public static final Codec<Map<ItemKey, ItemState>> ITEM_MAP_CODEC = Codec.list(
            RecordCodecBuilder.<Map.Entry<ItemKey,ItemState>>create(instance -> instance.group(
                    Item.CODEC.fieldOf(ITEM_ID_KEY).forGetter(e->e.getKey().item().builtInRegistryHolder()),
                    DataComponentPatch.CODEC.optionalFieldOf(COMPONENTS_KEY, DataComponentPatch.EMPTY).forGetter(e -> e.getKey().components()),
                    Codec.INT.fieldOf(ITEM_COUNT_KEY).forGetter(e -> e.getValue().count()),
                    Codec.LONG.fieldOf(LAST_MOD_TIME_LONG_KEY).forGetter(e -> e.getValue().lastModTime())
            ).apply(instance, (item, com, c, mod) -> Map.entry(new ItemKey(item, com), new ItemState(c, mod))))
    ).xmap(
            lst -> {
                Map<ItemKey, ItemState> map = new Object2ObjectLinkedOpenHashMap<>(lst.size());
                for(var e : lst) map.put(e.getKey(),e.getValue());
                return map;
            },
            map -> {
                var list = new ArrayList<Map.Entry<ItemKey, ItemState>>(map.size());
                map.forEach((k,v)->list.add(Map.entry(k,v)));
                return list;
            }
    );

    public static final Codec<EndlessInventory> CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
                    ITEM_MAP_CODEC.fieldOf(ITEM_LIST_KEY).forGetter(EndlessInventory::getItemMap),
                    EndInvAffinities.CODEC.fieldOf(AFFINITY_KEY).forGetter(endinv->endinv.affinities),
                    UUIDUtil.CODEC.fieldOf(UUID_KEY).forGetter(EndlessInventory::getUuid),
                    UUIDUtil.CODEC.fieldOf(OWNER_UUID_KEY).forGetter(endinv -> endinv.owner),
                    Codec.list(UUIDUtil.CODEC).fieldOf(WHITE_LIST_KEY).forGetter(ei -> ei.white_list),
                    Codec.STRING.xmap(Accessibility::valueOf, Accessibility::name).fieldOf(ACCESSIBILITY_KEY).forGetter(EndlessInventory::getAccessibility),
                    Codec.INT.fieldOf(MAX_STACK_SIZE_INT_KEY).forGetter(EndlessInventory::getMaxItemStackSize),
                    Codec.BOOL.fieldOf(INFINITY_BOOL_KEY).forGetter(EndlessInventory::isInfinityMode)
                        ).apply(instance, (itemMap, aff,uuid, ownerUuid, wLstUid, acc, maxSize, infBool) -> {
                            EndlessInventory endInv = new EndlessInventory(uuid, aff);
                               endInv.itemMap.putAll(itemMap);
                               endInv.owner = ownerUuid;
                               endInv.white_list.addAll(wLstUid);
                               endInv.setAccessibility(acc);
                               endInv.setMaxItemStackSize(maxSize);
                               endInv.setInfinityMode(infBool);
                               return endInv;
                    }
            )
    );

    @SuppressWarnings("unchecked")
    private final List<ItemStack>[] sortedViews = new List[SortType.values().length];

    private final long[] lastSortedTimes = new long[SortType.values().length];

    public List<ServerPlayer> viewers = new ArrayList<>();

    public EndlessInventory(){
        this(UUID.randomUUID());
    }

    public EndlessInventory(UUID uuid){
        this(uuid, new EndInvAffinities());
    }

    private EndlessInventory(UUID uuid, EndInvAffinities affinities){
        super(uuid, affinities);
    }

    protected List<ItemStack> getSortedView(SortType type, boolean reverse) {
        int idx = type.ordinal();
        List<ItemStack> result;
        synchronized (sortedViews) {
            if (lastSortedTimes[idx] != lastModTime || sortedViews[idx] == null) {
                List<ItemStack> view = snapshotItems();
                view.sort(ModInfo.sortHelper.getComparator(type, this));
                sortedViews[idx] = view;
                lastSortedTimes[idx] = lastModTime;
            }
            result = new ArrayList<>(sortedViews[idx]);
        }
        if(reverse) Collections.reverse(result);
        return result;
    }

    @Nullable
    public Optional<ServerPlayer> getOwner(ServerLevel level) {
        return level.getPlayers(pl->Objects.equals(pl.getUUID(),owner)).stream().findAny();
    }

    public void setChanged() {
        super.setChanged();
        ServerLevelEndInv.levelEndInvData.setDirty();
    }

    /**
     * Set endinv modState to new greater state.
     * @param newState should be greater than its original state
     * @return endinv's modState that has been updated
     */
    public long updateModState(long newState){
        if (newState <= lastModTime) {
            newState = lastModTime + 1;
        }
        this.lastModTime = newState;
        return lastModTime;
    }

    public void broadcastChanges(){//todo handle sending changes and client receiving ...
        this.viewers.forEach(player -> ServerLevelEndInv.checkAndGetManagerForPlayer(player)
                .ifPresent(manager -> {
                    //manager.getDisplayingPageId().syncContentToClient(player)
                }));
    }
}
