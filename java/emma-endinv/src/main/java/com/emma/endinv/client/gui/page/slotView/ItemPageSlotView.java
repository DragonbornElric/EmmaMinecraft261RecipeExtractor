package com.emma.endinv.client.gui.page.slotView;

import com.emma.endinv.SourceInventory;
import com.emma.endinv.client.gui.bg.IRectangleParam;
import com.emma.endinv.client.gui.page.GridPage;
import com.emma.endinv.client.gui.page.manager.ResourcePointer;
import com.emma.endinv.util.ItemKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

public class ItemPageSlotView extends Slot implements ResourcePointer<ItemStack>, SlotView, IRectangleParam {

    protected final GridPage page;
    protected final SourceInventory srcInv;
    @Nullable
    public final ItemKey itemKey;
    protected final int width, height;

    private boolean active = true;

    public ItemPageSlotView(PageViewContainer container, @Nullable ItemKey key, int slot, int x, int y, int width, int height) {
        super(container, slot, x, y);
        this.page = container.page;
        this.srcInv = page.srcInv;
        this.itemKey = key;
        this.width = width;
        this.height = height;
    }

    public ItemPageSlotView empty(){
        return new StandardItemPageSlotView((PageViewContainer) container, null, getContainerSlot(), this.x, this.y);
    }

    public ItemPageSlotView ofKey(ItemKey key){
        return new StandardItemPageSlotView((PageViewContainer) container, key, getContainerSlot(), this.x, this.y);
    }

    @Override
    public ItemStack get() {
        if(itemKey == null) return ItemStack.EMPTY;
        var state = srcInv.getItemMap().get(itemKey);
        if(state == null) return ItemStack.EMPTY;
        return itemKey.toStack(state.count());
    }

    /**
     * Renders the graphical user interface (GUI) element.
     *
     * @param guiGraphics the GuiGraphicsExtractor object used for rendering.
     * @param mouseX      the x-coordinate of the mouse cursor.
     * @param mouseY      the y-coordinate of the mouse cursor.
     * @param partialTick the partial tick time.
     */
    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        ItemStack stack = get();
        if(hasClickedOn(mouseX, mouseY)) renderSlotHighlightBack(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.item(stack, x, y, 0);
        guiGraphics.itemDecorations(Minecraft.getInstance().font, stack, x, y, page.getDisplayAmount(stack));
        if(hasClickedOn(mouseX, mouseY)) renderSlotHighlightFront(guiGraphics, mouseX, mouseY, partialTick);
        if(stack.isEmpty() && !stack.is(Items.AIR)) page.renderEmpty(guiGraphics, x, y, stack);
    }

    @Override
    public void renderSlotHighlightBack(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick){
        guiGraphics.fill(x, y, x+width - 2, y+height - 2, 0x80ffffff);
    }

    @Override
    public void renderSlotHighlightFront(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick){
        guiGraphics.fill(x, y, x+width - 2, y+height - 2, 0x20ffffff);
        ItemStack hovering = get();
        if(hovering.isEmpty()) return;
        guiGraphics.setTooltipForNextFrame(
                Minecraft.getInstance().font,
                AbstractContainerScreen.getTooltipFromItem(Minecraft.getInstance(), hovering),
                hovering.getTooltipImage(),
                mouseX, mouseY
        );
    }

    @Override
    public void onTake(Player player, ItemStack stack) {
        super.onTake(player, stack);
    }

    /**
     * Check if the stack is allowed to be placed in this slot, used for armor slots as well as furnace fuel.
     *
     */
    @Override
    public boolean mayPlace(ItemStack stack) {
        return super.mayPlace(stack);
    }

    @Override
    public ItemStack getItem() {
        return get();
    }

    @Override
    public boolean hasItem() {
        return this.itemKey!=null && srcInv.getItemMap().get(itemKey) != null;
    }

    @Override
    public void set(ItemStack stack) throws UnsupportedOperationException{
        throw new UnsupportedOperationException();
    }

    @Override
    public void setChanged() {
        super.setChanged();
    }

    @Override
    public int getMaxStackSize() {
        return srcInv.getMaxItemStackSize();
    }

    @Override
    public int getMaxStackSize(ItemStack stack) {
        return srcInv.getMaxItemStackSize();
    }

    /**
     * Decrease the size of the stack in slot (first int arg) by the amount of the second int arg. Returns the new stack.
     * */
    @Override
    public ItemStack remove(int amount) {
        if(itemKey == null) return ItemStack.EMPTY;
        return srcInv.takeItem(itemKey, amount);
    }

    public ItemStack remove(){
        if(itemKey == null) return ItemStack.EMPTY;
        return srcInv.takeItem(get());
    }

    /**
     * Return whether this slot's stack can be taken from this slot.
     *
     */
    @Override
    public boolean mayPickup(Player player) {
        return super.mayPickup(player);
    }

    @Override
    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active){
        this.active = active;
    }

    @Override
    public Optional<ItemStack> tryRemove(int count, int decrement, Player player) {
        if (!this.mayPickup(player)) {
            return Optional.empty();
        } else if (!this.allowModification(player) && decrement < this.getItem().getCount()) {
            return Optional.empty();
        } else {
            count = Math.min(count, decrement);
            ItemStack itemstack = this.remove(count);
            if (itemstack.isEmpty()) {
                return Optional.empty();
            } else {
                return Optional.of(itemstack);
            }
        }
    }

    @Override
    public ItemStack safeTake(int count, int decrement, Player player) {
        return super.safeTake(count, decrement, player);
    }

    @Override
    public ItemStack safeInsert(ItemStack stack) {
        return srcInv.addItem(stack);
    }

    @Override
    public ItemStack safeInsert(ItemStack stack, int increment) {
        return srcInv.addItem(stack.copyWithCount(Math.min(stack.getCount(), increment)));
    }

    @Override
    public boolean allowModification(Player player) {
        return super.allowModification(player);
    }

    @Override
    public int getContainerSlot() {
        return super.getContainerSlot();
    }

    @Override
    public int x() {
        return x;
    }

    @Override
    public int y() {
        return y;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public boolean hasClickedOn(double mouseX, double mouseY) {
        return mouseX>= x && mouseX< x + width && mouseY>= y && mouseY< y + height;
    }
}
