package com.emma.endinv.client;

import com.emma.endinv.EndInvAffinities;
import com.emma.endinv.ModInfo;
import com.emma.endinv.SourceInventory;
import com.emma.endinv.network.payloads.toClient.EndInvMetadata;
import com.emma.endinv.options.ContentTransferMode;
import com.emma.endinv.options.ServerConfigs;
import com.emma.endinv.util.ItemKey;
import com.emma.endinv.util.ItemState;
import com.emma.endinv.util.SearchUtil;
import com.emma.endinv.util.SortType;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Unmodifiable;

import org.jetbrains.annotations.Nullable;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**Client only {@link SourceInventory}
 * Served as data cache of EndlessInventory.
 */
public class CachedSrcInv extends SourceInventory {

    public static final CachedSrcInv INSTANCE = new CachedSrcInv();

    private int itemSize;
    //controls whether itemSize data is valid when transfermode==all.
    private boolean validSize;

    private CachedSrcInv(){
        super(ModInfo.DEFAULT_UUID, new EndInvAffinities());
    }

    public void initializeContents(Map<ItemKey, ItemState> itemMap){
        overwriteItems(new Object2ObjectLinkedOpenHashMap<>(itemMap));
        this.itemSize = getItemSize();//here assert transfermode==all //parallelable
        this.validSize = true;
    }
    @Unmodifiable
    public List<ItemKey> getItemView(int startIndex,
                                     int length,
                                     SortType sortType,
                                     boolean reverse,
                                     @Nullable Predicate<ItemStack> classify,
                                     String search){
        var ret = getSortedKeyReference(sortType)
                .filter(key -> {
                    ItemStack stack = key.toStack(itemMap.get(key).count());
                    return (classify == null || classify.test(stack)) && SearchUtil.matchesSearch(stack, search);
                })
                .toList();
        int size = ret.size();
        //update startIndex
        if(size <= length) startIndex = 0;
        //startIndex = Mth.clamp(startIndex, 0, size - length);
        ret = ret.subList(Mth.clamp(startIndex, 0, size), Math.min(startIndex + length, size));
        return reverse ? ret.reversed() : ret;
    }

    /**
     * @return Size of Endless Inventory.
     */
    @Override
    public int getItemSize() {
        if(ServerConfigs.ENDINV_BEHAVIOR.TransferMode.get()== ContentTransferMode.PART) return itemSize;
        if(this.validSize) return itemSize;
        return super.getItemSize();
    }

    @Override
    public ItemStack takeItem(ItemStack stack, int count){
        if(stack.isEmpty()) return ItemStack.EMPTY;
        ItemKey key = ItemKey.asKey(stack);
        ItemState state = itemMap.get(key);
        if (state == null) return ItemStack.EMPTY;
        //if infinity
        if(state.count() >= maxStackSize && infinityMode){
            setChanged();
            return stack.copyWithCount(count);
        }

        int taken = Math.min(count, state.count());
        ItemStack result = stack.copyWithCount(taken);
        if (taken == state.count()) {
            itemMap.remove(key);
            updateLastModTime();
        } else {
            itemMap.put(key, new ItemState(state.count() - taken, updateLastModTime()));
        }
        setChanged();
        return result;

    }

    @Override
    public ItemStack addItem(ItemStack itemStack){
        if(itemStack.isEmpty()) return ItemStack.EMPTY;
        ItemKey key = ItemKey.asKey(itemStack);
        ItemState state = itemMap.get(key);
        int count = itemStack.getCount();
        int original = 0;

        if (state != null) {
            original = state.count();
        }
        int increased;
        if(original < maxStackSize){
            increased = original+count;
            if(increased <= maxStackSize){
                itemMap.put(key, new ItemState(increased, updateLastModTime()));
                setChanged();
                return ItemStack.EMPTY;
            }else {
                itemMap.put(key, new ItemState(maxStackSize, updateLastModTime()));
                setChanged();
                return itemStack.copyWithCount(increased-maxStackSize);
            }
        }else if(infinityMode){
            itemMap.put(key, new ItemState(original, updateLastModTime()));
            setChanged();
            return ItemStack.EMPTY;
        }else {
            return itemStack.copy();
        }
    }

    @Override
    public void setChanged() {
        super.setChanged();
        validSize = false;
    }

    @Override
    public Map<ItemKey, ItemState> getItemMap() {
        return itemMap;
    }

    public long updateLastModTime(){
        return Util.getMillis();
    }

    public void syncMetadata(EndInvMetadata endInvMetadata) {
        this.itemSize = endInvMetadata.itemSize();
        this.maxStackSize = endInvMetadata.maxStackSize();
        this.infinityMode = endInvMetadata.infinityMode();
        this.accessibility =endInvMetadata.config().accessibility();
        this.owner = endInvMetadata.config().owner();
        this.white_list = endInvMetadata.config().white_list();
    }
}
