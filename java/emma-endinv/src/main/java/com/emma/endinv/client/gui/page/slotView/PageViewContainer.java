package com.emma.endinv.client.gui.page.slotView;

import com.emma.endinv.client.gui.page.GridPage;
import com.emma.endinv.util.ItemKey;
import net.minecraft.core.NonNullList;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public class PageViewContainer implements Container {

    public final GridPage page;
    protected final int size;
    protected final NonNullList<ItemPageSlotView> slots;
    private final int x;
    private final int y;

    public PageViewContainer(GridPage page, int size, int startX, int startY){
        this.page = page;
        this.size = size;
        this.slots = NonNullList.createWithCapacity(size);
        this.x = startX;
        this.y = startY;
        int col = page.framework.columns();
        addSlots(List.of(), col, startX, startY);
    }

    public PageViewContainer(GridPage page, List<ItemKey> itemView, int row, int col, int startX, int startY){
        this.page = page;
        this.size = row * col;
        this.slots = NonNullList.createWithCapacity(size);
        this.x = startX;
        this.y = startY;
        addSlots(itemView, col, startX, startY);
    }

    protected void addSlots(List<ItemKey> itemView, int col, int startX, int startY) {
        for(int i = 0; i < size; ++i ){
            int x = startX + i % col * 18;
            int y = startY + i / col * 18;
            if(i < itemView.size()) {
                slots.add(i, new StandardItemPageSlotView(this, itemView.get(i), i, x, y));
            }else {
                slots.add(i, new StandardItemPageSlotView(this, null, i, x, y));
            }
        }
    }

    public NonNullList<ItemPageSlotView> slots(){
        return slots;
    }

    @Override
    public int getContainerSize() {
        return size;
    }

    @Override
    public boolean isEmpty() {
        return slots.stream().allMatch(p -> p.get().isEmpty());
    }

    /**
     * Returns the stack in the given slot.
     */
    @Override
    public ItemStack getItem(int slot) {
        return slots.get(slot).get();
    }

    /**
     * Removes up to a specified number of items from an inventory slot and returns them in a new stack.
     */
    @Override
    public ItemStack removeItem(int slot, int amount) {
        return slots.get(slot).remove(amount);
    }

    /**
     * Removes a stack from the given slot and returns it.
     */
    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return slots.get(slot).remove();
    }

    /**
     * Sets the given item stack to the specified slot in the inventory (can be crafting or armor sections).
     */
    @Override
    public void setItem(int slot, ItemStack stack) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setChanged() {
        page.setChanged();
    }

    /**
     * Don't rename this method to canInteractWith due to conflicts with Container
     */
    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void clearContent() {
        slots.replaceAll(ItemPageSlotView::empty);
    }
}
