package com.emma.endinv.client.gui.page;

import com.emma.endinv.client.gui.ScreenFramework;
import com.emma.endinv.integrate.FluidData;
import com.emma.endinv.menu.page.PageType;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.NonNullList;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.NotNull;

public abstract class CommonFluidPage<FS> extends GridPage{

    protected NonNullList<FS> fluids;
    protected FS unit = unit();

    protected int startIndex = 0;
    protected int length;


    public CommonFluidPage(PageType pageType, ScreenFramework framework) {
        super(pageType, framework);
    }

    @Override
    protected void setVisibleRange(int startIndex, int length) {
        this.startIndex = startIndex;
        this.length = Math.min(length, framework.rows()* framework.columns());
        this.fluids = NonNullList.withSize(length, unit);
        refreshFluids();
    }

    /**
     * Should return a static FluidStack instance like FluidStack#EMPTY
     * @return a nonnull static object representing empty fluid stack
     */
    @NotNull
    protected abstract FS unit();

    protected abstract void refreshFluids();

    /**
     * Controls whether page will change view when mouse scrolled up and down
     *
     * @return true if scroll can be performed
     */
    @Override
    public boolean canScroll() {
        return getStartIndex() >0 ||(getStartIndex() +length <= srcInv.affinities.fluids.size());
    }

    /**
     * The change usually means pageMetaData changes and called by framework in sort,search,... changes.
     */
    @Override
    public void sendChangesToServer() {

    }

    @Override
    public boolean hasSearchbox() {
        return true;
    }

    @Override
    public boolean hasSortTypeSwitchBar() {
        return true;
    }

    @Override
    public abstract void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks);
//        int rowIndex = 0;
//        int columnIndex = 0;
//        for(FluidData fluid : fluids){
//            int itemX = leftPos + MARGIN_SIDE_WIDTH + columnIndex*18,itemY = topPos + MARGIN_TOP_HEIGHT + rowIndex*18+1;
//            //if(fluid.isEmpty() && !Objects.equals(fluid.id(),"air")) renderEmpty(graphics,itemX,itemY,fluid);
//            renderFluid(graphics,fluid,itemX,itemY);
//            if(!isHiddenBySortBox(rowIndex,columnIndex))
//                renderFluidDecorations(graphics,fluid,itemX,itemY);
//            columnIndex++;
//            if(columnIndex>= framework.columns()){
//                columnIndex=0;
//                rowIndex++;
//            }
//        }


//    /**
//     * Render a fluid stack in a slot.
//     * @param fluid the fluid data and should convert it to the Fluid stack instance.
//     */
//    protected abstract void renderFluid(GuiGraphicsExtractor graphics, FluidData fluid, int x, int y);

    /**only render count in this raw method
     * @param x fluid slot x
     * @param y fluid slot y
     */
    protected void renderFluidDecorations(GuiGraphicsExtractor graphics, FluidData fluid, int x, int y){
        String s = getDisplayAmount(fluid);
        Font font = Minecraft.getInstance().font;
        graphics.text(font, s, x + 19 - 2 - font.width(s), y + 6 + 3, -1, true);
    }

    protected String getDisplayAmount(FluidData fluid){
        long count = fluid.amountMB();
        double value;
        String suffix = "mB";

//        if(count == this.framework.getMaxStackSize() && framework.enableInfinity()){
//            return "∞";
//        }

        if (count >= 1_000_000_000_000L) {
            value = count / 1_000_000_000_000.0;
            suffix = "GB";
        } else if (count >= 1_000_000_000) {
            value = count / 1_000_000_000.0;
            suffix = "MB";
        } else if (count >= 1_000_000) {
            value = count / 1_000_000.0;
            suffix = "KB";
        } else if (count >= 1_000) {
            value = count / 1_000.0;
            suffix = "B";
        }else if(count==0){
            return ChatFormatting.RED + "0";
        }else {
            value = count;
        }

        return String.format("%.1f%s", value, suffix);
    }

    @Override
    public void pageClicked(double XOffset, double YOffset, int keyCode, ContainerInput clickType) {
        int i = getSlotByMouseOffset(XOffset,YOffset);
        if(i >= 0 && i < fluids.size()){
            FS clicked = fluids.get(i);
            ItemStack carried = menu.getCarried();
            switch (clickType){
                case PICKUP -> handlePickup(clicked, carried, keyCode);
                case QUICK_MOVE -> handleQuickMove(clicked, carried, keyCode);
                case SWAP -> handleSwap(clicked, carried, keyCode);
                case THROW -> handleThrow(clicked,carried,keyCode);
                case CLONE -> handleClone(clicked, carried,keyCode);
                case PICKUP_ALL -> handlePickUpAll(clicked,carried,keyCode);
            }
        }
    }

    /// separate detailed click methods to let override easily.

    /**
     * Extract fluid from fluidContainer.
     * @param fluidContainer if empty, let page select container, default is {@link Items#BUCKET}.
     * @return extracted fluid in the container, or empty if failed.
     */
    public abstract ItemStack extractFluid(ItemStack fluidContainer);

    /**Invoked when carrying an item on the blank part of the fluid page.
     * Insert fluid into EndInv from fluidContainer. If the item is not a fluidContainer, perform {@link #tryInsertItem(ItemStack)}
     * @param fluidContainer the itemStack carrying and to perform insert action with the fluid page.
     * @param button 1 for put all, 2 for put 1 Block fluid.
     * @return the container remained after insert.
     */
    public abstract ItemStack insertFluidAction(ItemStack fluidContainer, int button);

    protected abstract void handlePickup(FS clicked, ItemStack carried, int keyCode);/*{
        if(!clicked.isEmpty()){
            ItemStack bucket = extractFluid(carried);
            menu.setCarried(bucket);
        } else {
            ItemStack remain = insertFluidAction(carried,keyCode);
            *//*if(!remain.isEmpty())*//* menu.setCarried(remain);
        }
    }*/

    protected abstract void handleQuickMove(FS clicked, ItemStack carried, int keyCode);/*{
        if(!clicked.isEmpty()){
            ItemStack bucket = extractFluid(carried);
            if(!bucket.isEmpty()){
                ItemStack remain = framework.quickMoveFromPage(bucket);
                *//*if(!remain.isEmpty())*//* menu.setCarried(remain);
            }
        } else {
            ItemStack remain = insertFluidAction(carried,keyCode);
            remain = framework.quickMoveFromPage(remain);
            *//*if(!remain.isEmpty())*//* menu.setCarried(remain);
        }
    }*/

    protected abstract void handleSwap(FS clicked, ItemStack carried, int inventorySlotId);/*{
        Player player = framework.getPlayer();
        Inventory inventory = player.getInventory();
        ItemStack inventoryItem = inventory.getItem(inventorySlotId);
        boolean a = !inventoryItem.isEmpty();
        boolean b = !clicked.isEmpty();
        if( a && !b ){
            ItemStack remain = tryInsertItem(inventoryItem);
            inventory.setItem(inventorySlotId, remain);
        }
        if( !a && b ){
            ItemStack bucket = extractFluid(carried);
            inventory.setItem(inventorySlotId, bucket);
        }
        if( a && b ){
            ItemStack remain = tryInsertItem(inventoryItem);
            if(remain.isEmpty()) {
                ItemStack bucket = extractFluid(carried);
                inventory.setItem(inventorySlotId, bucket);
            } else {
                inventory.setItem(inventorySlotId,remain);
            }
        }
    }*/

    protected abstract void handleThrow(FS clicked, ItemStack carried, int button);/*{
        Player player = framework.getPlayer();
        if(!clicked.isEmpty()){
            ItemStack bucket = extractFluid(carried);
            player.drop(bucket,true);
        } else {
            ItemStack remain = insertFluidAction(carried,button);
            *//*if(!remain.isEmpty())*//* player.drop(remain,true);
        }
    }*/

    /**
     * handle creative clone fluid, equals to extract fluid without consume EndInv's fluid
     * @param clicked should not be empty for creative clone
     * @param carried should be empty for creative clone
     * @param button ignore (default to 3)
     */
    protected abstract void handleClone(FS clicked, ItemStack carried, int button);

    /**
     * Collect all the same fluids in inventory's fluid containers..?
     * @param clicked to collect
     * @param carried should be empty for pickup all
     * @param keyCode ignore (default to 0)
     */
    protected abstract void handlePickUpAll(FS clicked, ItemStack carried, int keyCode);
}
