package com.emma.endinv.client.gui.page;

import com.emma.endinv.client.gui.ScreenFramework;
import com.emma.endinv.client.gui.page.slotView.PageViewContainer;
import com.emma.endinv.client.gui.page.slotView.SegmentedViewContainer;
import com.emma.endinv.menu.page.PageType;
import com.emma.endinv.util.ItemKey;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Item display page that groups entries into segments defined by sub classifications.
 * Each segment is rendered on its own set of rows. Items that do not match any subclassify
 * predicate can optionally form an additional trailing segment.
 */
public class SegClassifyItemDisplay extends ItemDisplay {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final List<Predicate<ItemKey>> subClassifies;
    private final boolean includeRemainItems;
    private final boolean keepClassifiedItemInNextSeg;

    public SegClassifyItemDisplay(PageType pageType,
                                  ScreenFramework screenFramework,
                                  @Nullable List<Predicate<ItemKey>> subClassifies,
                                  boolean includeRemainItems,
                                  boolean keepClassifiedItemInNextSeg
    ) {
        super(pageType, screenFramework);
        this.subClassifies = subClassifies == null ? List.of() : List.copyOf(subClassifies);
        this.includeRemainItems = includeRemainItems;
        this.keepClassifiedItemInNextSeg = keepClassifiedItemInNextSeg;
    }

    @Override
    protected PageViewContainer buildView(List<ItemKey> items) {
        return new SegmentedViewContainer(
                this,
                new ArrayList<>(items),//to be modifiable
                framework.rows(),
                framework.columns(),
                getPageLeft() + MARGIN_SIDE_WIDTH,
                getPageTop() + MARGIN_TOP_HEIGHT,
                subClassifies,
                includeRemainItems,
                keepClassifiedItemInNextSeg
        );
    }
}
