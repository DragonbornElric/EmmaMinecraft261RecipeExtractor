package com.emma.endinv.menu.page.pageManager;

import com.emma.endinv.EndlessInventory;
import com.emma.endinv.SourceInventory;
import com.emma.endinv.menu.page.PageType;
import com.emma.endinv.menu.page.PageTypeRegistry;
import com.emma.endinv.network.payloads.PageData;
import com.emma.endinv.util.SortType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**Reserved server side page manager that can monitor players' EI Attaching status.
 * <br>
 * Originally it's called {@code AttachingManager} and serves menus like utilities server side.
 * Because in version 1.0.0 pages are both on server and client same as Minecraft menus.
 * When I tried to add more client features like resize, the page had been only kept on client.
 * <p>
 *     Get it on server: {@link com.emma.endinv.ServerLevelEndInv#checkAndGetManagerForPlayer(ServerPlayer)}
 * </p>
 * @since 25.10.12
 * @author Kay
 * @version 1.1.0
 */
public class AttachingMonitor implements PageMetaDataManager{

    private final AbstractContainerMenu menu;
    private final EndlessInventory endinv;
    private final ServerPlayer player;
    //client for page switch
    private final List<String> PageIdList;
    private String DisplayingPageId;
    private int displayingPageIndex;
    private final PageQuickMoveHandler quickMoveHandler;
    public SortType sortType;
    public String searching;
    private int rows;
    private int columns;
    private boolean reverseSort;

    public AttachingMonitor(AbstractContainerMenu menu, EndlessInventory endinv, ServerPlayer player){
        this.menu = menu;
        this.endinv = endinv;
        this.player = player;
        PageIdList = PageTypeRegistry.getIdList();
        this.rows = 9;
        this.columns = 9;
        this.sortType = SortType.DEFAULT;
        this.searching = "";
        this.reverseSort = false;
        if (!PageIdList.isEmpty()) {
            this.displayingPageIndex = 0;
            this.DisplayingPageId = PageIdList.get(0);
        } else {
            this.displayingPageIndex = 0;
            this.DisplayingPageId = PageType.DEFAULT_KEY;
        }
        this.quickMoveHandler = new PageQuickMoveHandler(this);
    }

    public void applyPageData(PageData data){
        this.rows = data.rows();
        this.columns = data.columns();
        this.sortType = data.sortType();
        this.searching = data.search();
        this.reverseSort = data.reverseSort();
        this.switchPageWithId(data.pageRegKey());
    }


    @Override
    public AbstractContainerMenu getMenu() {
        return menu;
    }

    @Override
    public SourceInventory getSourceInventory() {
        return endinv;
    }

    @Override
    public void switchPageWithIndex(int index) {
        displayingPageIndex = index;
        DisplayingPageId = PageIdList.get(index);
    }

    @Override
    public int rows() {
        return rows;
    }

    @Override
    public int columns() {
        return columns;
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    @Override
    public int getItemSize() {
        return endinv.getItemSize();
    }

    @Override
    public int getMaxStackSize() {
        return endinv.getMaxItemStackSize();
    }

    @Override
    public boolean enableInfinity() {
        return endinv.isInfinityMode();
    }

    @Override
    public ItemStack quickMoveFromPage(ItemStack stack) {
        return this.quickMoveHandler.quickMoveFromPage(stack);
    }

    @Override
    public SortType sortType() {
        return sortType;
    }

    @Override
    public void setSortType(SortType sortType) {
        this.sortType = sortType;
    }

    @Override
    public boolean isSortReversed() {
        return this.reverseSort;
    }

    @Override
    public void switchSortReversed() {
        this.reverseSort = !this.reverseSort;
    }

    @Override
    public void setSortReversed(boolean reversed) {
        this.reverseSort = reversed;
    }

    @Override
    public String searching() {
        return searching;
    }

    @Override
    public void setSearching(String searching) {
        this.searching= searching;
    }
/*    public void sendEndInvData() {
        var distributor = ModInfo.getPacketDistributor();
        var cfg = ModInfo.getServerConfig();
        distributor.sendToPlayer(player, EndInvMetadata.getWith(endinv));
        switch (cfg.transferMode().get()) {
            case ALL -> distributor.sendToPlayer(player, new EndInvContent(endinv.getItemMap()));
            case PART -> {
                int length = rows * columns;
                var view = endinv.getSortedAndFilteredItemView(0, length, sortType, reverseSort, getDisplayingPageType().itemClassify, searching);
                net.minecraft.core.NonNullList<net.minecraft.world.item.ItemStack> stacks = net.minecraft.core.NonNullList.withSize(length, net.minecraft.world.item.ItemStack.EMPTY);
                for (int i = 0; i < view.size() && i < length; ++i) {
                    stacks.set(i, view.get(i));
                }
                distributor.sendToPlayer(player, new SetItemDisplayContentPayload(stacks));
            }
        }
    }*/

    @Override
    public void switchPageWithId(String id) {
        DisplayingPageId = id;
        for(int i=0; i<PageIdList.size();++i){
            if(PageIdList.get(i).equals(id)){
                displayingPageIndex = i;
            }
        }
    }

    @Override
    public String getDisplayingPageId() {
        return DisplayingPageId;
    }

    @Override
    public PageType getDisplayingPageType() {
        return PageTypeRegistry.byId(DisplayingPageId);
    }
}


