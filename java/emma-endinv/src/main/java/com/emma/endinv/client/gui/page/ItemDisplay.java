package com.emma.endinv.client.gui.page;

import com.emma.endinv.client.CachedSrcInv;
import com.emma.endinv.client.gui.ScreenFramework;
import com.emma.endinv.menu.page.PageType;
import com.emma.endinv.util.ItemKey;

import java.util.List;

/**Page that displays EndInv's items (directly {@link CachedSrcInv})
 *
 */
public class ItemDisplay extends ItemPage{

    public ItemDisplay(PageType pageType, ScreenFramework framework) {
        super(pageType,framework);
    }

    public void refreshItems(){
        requestRemoteContents();
        readCachedItems();
    }

    /**
     * Read items data from {@link CachedSrcInv} when transfer mode is {@code ALL} and rebuild the view.
     */
    public void readCachedItems(){
        List<ItemKey> view = getViewForPage();
        buildContentsWith(view);
    }

    public void requestRemoteContents(){
        sendChangesToServer();
    }

    /**
     * Build Displayed view with a ItemStack list.
     * @param stacks itemstack list to fill the view
     */
    public void buildContentsWith(List<ItemKey> stacks){
        if(holdOn){
            inQueueStacks = stacks;
            return;
        }
        this.viewContainer = buildView(stacks);
    }

    @Override
    public boolean hasSearchbox() {
        return true;
    }

    @Override
    public boolean hasSortTypeSwitchBar() {
        return true;
    }

    // Pointer-based: rely on srcInv and server to update; no local animation here.

    public void release(){
        if(holdOn){
            holdOn = false;
            if(inQueueStacks==null) return;
            buildContentsWith(inQueueStacks);
        }
    }
}
