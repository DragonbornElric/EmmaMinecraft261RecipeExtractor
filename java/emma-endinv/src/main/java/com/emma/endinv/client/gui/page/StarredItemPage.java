package com.emma.endinv.client.gui.page;

import com.emma.endinv.client.CachedSrcInv;
import com.emma.endinv.client.gui.ScreenFramework;
import com.emma.endinv.menu.page.PageType;
import com.emma.endinv.network.payloads.toServer.StarItemPayload;
import com.emma.endinv.util.ItemKey;
import com.emma.endinv.util.ItemStackLike;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.List;

import static com.emma.endinv.ModInfo.getPacketDistributor;

public class StarredItemPage extends ItemDisplay{

    public Identifier icon = Identifier.fromNamespaceAndPath("minecraft", "book");
    private int[] countArray;

    public StarredItemPage(PageType type, ScreenFramework screenFramework) {
        super(type, screenFramework);
    }

    @Override
    protected List<ItemKey> getViewForPage() {
        return CachedSrcInv.INSTANCE.affinities.starredItems;
    }

    public void starItem(ItemStack stack, boolean isAdding){
        if(stack.isEmpty()) return;
        getPacketDistributor().sendToServer(new StarItemPayload(stack,isAdding));
        requestRemoteContents();
    }

    @Override
    protected void setVisibleRange(int startIndex, int length){
        this.startIndex = startIndex;
        this.length = Math.min(length, framework.rows()* framework.columns());
        this.countArray = new int[length];
        this.refreshItems();
    }

    /**
     * The <em>refresh</em> method of ItemPage, this method shall keep the startIndex and length and fill {@link #items}
     * with such and srcInv.
     */
    @Override
    public void refreshItems() {
        requestRemoteContents();
    }

    public void initializeAsMap(List<ItemStackLike> stacks){
        this.viewContainer = buildView(stacks.stream().map(ItemStackLike::toKey).toList());
    }

    public void requestRemoteContents(){
        getPacketDistributor().sendToServer(new StarItemPayload(ItemStack.EMPTY,false));
    }

    @Override
    public void handleStarItem(double XOffset, double YOffset) {
        starItem(getItemByMouseOffset(XOffset, YOffset), false);
    }
}
