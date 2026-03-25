package com.emma.endinv.client.gui.page.slotView;

import com.emma.endinv.client.gui.page.GridPage;
import com.emma.endinv.client.gui.page.ItemEntryDisplay;
import com.emma.endinv.util.ItemKey;

import java.util.List;

public class EntryPageViewContainer extends PageViewContainer{

    public final ItemEntryDisplay.DescriptionProvider entryProvider;
    //size == row
    public EntryPageViewContainer(GridPage page, List<ItemKey> itemView, int row, int startX, int startY, ItemEntryDisplay.DescriptionProvider entryProvider) {
        super(page, itemView, row, 1, startX, startY);
        this.entryProvider = entryProvider;
    }
    @Override
    protected void addSlots(List<ItemKey> itemView, int col, int startX, int startY) {
        for(int i = 0; i < size; ++i ){
            int y = startY + i * 18;
            if(i < itemView.size()) {
                slots.add(i, new ItemEntryPageSlotView(this, itemView.get(i), i, startX, y));
            }else {
                slots.add(i, new ItemEntryPageSlotView(this, null, i, startX, y));
            }
        }
    }
}
