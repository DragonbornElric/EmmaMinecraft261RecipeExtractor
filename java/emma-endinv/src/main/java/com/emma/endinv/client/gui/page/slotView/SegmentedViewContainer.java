package com.emma.endinv.client.gui.page.slotView;

import com.emma.endinv.client.gui.page.GridPage;
import com.emma.endinv.util.ItemKey;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class SegmentedViewContainer extends PageViewContainer{

    private final List<Predicate<ItemKey>> seg;
    private final boolean includeRemainItems;
    private final boolean keepClassifiedItemInNextSeg;
    @Nullable
    private Runnable deferredAddSlots;

    /**
     * Constructs a new SegmentedViewContainer instance, allowing items to be displayed
     * in segments based on provided predicates, with configurable options for handling
     * remaining items and classified items.
     *
     * @param page The grid page instance this container is associated with.
     * @param itemView @Modifiable A list of items that will be displayed in the grid after segmentation.
     * @param row The number of rows in the grid.
     * @param col The number of columns in the grid.
     * @param startX The x-coordinate for the starting position of the grid.
     * @param startY The y-coordinate for the starting position of the grid.
     * @param segmentPredicates A list of predicates for segmenting items into groups.
     * @param includeRemainItems If true, all remaining items that do not match any predicate
     *                           will be included in the last segment.
     * @param keepClassifiedItemInNextSeg If true, classified items are retained in the item view
     *                                    for potential inclusion in subsequent segments.
     */
    public SegmentedViewContainer(GridPage page,List<ItemKey> itemView, int row, int col, int startX, int startY,
                                  List<Predicate<ItemKey>> segmentPredicates,
                                  boolean includeRemainItems,
                                  boolean keepClassifiedItemInNextSeg) {
        super(page, itemView, row, col, startX, startY);
        this.seg = segmentPredicates;
        this.includeRemainItems = includeRemainItems;
        this.keepClassifiedItemInNextSeg = keepClassifiedItemInNextSeg;
        deferredAddSlots.run();
    }

    @Override
    protected void addSlots(List<ItemKey> itemView, int col, int startX, int startY) {
        this.deferredAddSlots = () -> deferredAddSlots(itemView, col, startX, startY);
    }

    private void deferredAddSlots(List<ItemKey> itemView, int col, int startX, int startY){
        Iterator<Predicate<ItemKey>> it = seg.iterator();
        List<ItemKey> segment = null;
        for(int i = 0; i < size; ++i ) {
            int x = startX + i % col * 18;
            int y = startY + i / col * 18;


            if (segment == null || segment.isEmpty()) {
                if (i % col == 0) {
                    if (it.hasNext()) {
                        segment = itemView.stream().filter(it.next()).collect(Collectors.toList());//.to be modifiable
                        if(!keepClassifiedItemInNextSeg) itemView.removeAll(segment);
                    }else if(includeRemainItems){
                        segment = itemView;
                    }
                }
                if (segment == null || segment.isEmpty()) {
                    slots.add(i, new StandardItemPageSlotView(this, null, i, x, y));
                    continue;
                }
            }
            slots.add(i, new StandardItemPageSlotView(this, segment.removeFirst(), i, x, y));
        }
    }
}
