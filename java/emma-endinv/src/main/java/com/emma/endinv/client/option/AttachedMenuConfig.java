package com.emma.endinv.client.option;

import com.emma.endinv.client.gui.bg.IRectangleParam;
import com.emma.endinv.menu.page.PageTypeRegistry;
import com.emma.endinv.options.config.ComplexConfigEntryImpl;
import com.emma.endinv.options.config.ConfigEntryImpl;
import com.emma.endinv.options.config.EntryPresentable;

import java.util.List;

public class AttachedMenuConfig extends ComplexConfigEntryImpl<AttachedMenuScreenLayout> {
    
    public static final AttachedMenuConfig INSTANCE = new AttachedMenuConfig("Attached Screen(Menu) Options", new String[]{"Attached Menu Options"});
    
    public final EnumEntry<AttachedMenuScreenLayout.BasicLayout> BasicLayout = new EnumEntry<>(
            "BasicLayout", new String[]{"The basic layout of attached screen."}, AttachedMenuScreenLayout.BasicLayout.LEFT);
    public final IntEntry LeftPos = new IntEntry(
            "LeftPos", new String[]{"The left position of attached screen."}, 20, Integer.MIN_VALUE, Integer.MAX_VALUE);
    public final IntEntry TopPos = new IntEntry(
            "TopPos", new String[]{"The top position of attached screen."}, 20, Integer.MIN_VALUE, Integer.MAX_VALUE);
    public final PageBasicLayoutConfig PageBasicLayout = new PageBasicLayoutConfig(
            "PageBasicLayout", new String[]{"The basic layout of page."}, PageBasicLayoutConfig.Param.DEFAULT);
    public final ListEntry<String> DontDisplayPages = new ListEntry<>(
            "HidePages",
            new String[]{"The pages will not have a tab in the page switch bar to be switched to"},
            List.of()
    );
    public final EnumEntry<TextureMode> TextureMode = new EnumEntry<>(
            "TextureMode",
            EntryPresentable.generateComments(TextureMode.class, "Controls the texture of Screen's widgets."),
            com.emma.endinv.client.option.TextureMode.FROM_RESOURCE
    );
    public final ListEntry<Integer> PageRectangle = IRectangleParam.createRectangleConfigEntry(
            "PageRectangleParam", new String[]{"The rectangle of page and is active when TextureMode is Transparent"}, AttachedMenuScreenLayout.PAGE_RECTANGLE
    );//todo detailed description
    
    public final PageSwitchBarConfig PageSwitchBar = PageSwitchBarConfig.INSTANCE;
    
    public final ListEntry<Integer> SearchBoxParam = IRectangleParam.createRectangleConfigEntry(
            "SearchBoxParam", new String[]{"The search box parameters"}, AttachedMenuScreenLayout.SEARCH_BOX);
    public final ListEntry<Integer> SortBoxParam = IRectangleParam.createRectangleConfigEntry(
            "SortBoxParam", new String[]{"The sort box parameters"}, AttachedMenuScreenLayout.SORT_BOX);
    public final ListEntry<Integer> ReverseSortButtonParam = IRectangleParam.createRectangleConfigEntry(
            "ReverseSortButtonParam", new String[]{"The reverse sort button parameters"}, AttachedMenuScreenLayout.REVERSE_SORT_BUTTON);
    public final ListEntry<Integer> ConfigButtonParam = IRectangleParam.createRectangleConfigEntry(
            "ConfigButtonParam", new String[]{"The config button parameters"}, AttachedMenuScreenLayout.CONFIG_BUTTON);
    
    
    public AttachedMenuConfig(String key, String[] comments) {
        super(key, comments, AttachedMenuScreenLayout.getDefault());
    }
    
    @Override
    public AttachedMenuScreenLayout get() {
        AttachedMenuScreenLayout.BasicLayout basicLayout = BasicLayout.get();
        switch (basicLayout){
            case LEFT: {
                return AttachedMenuScreenLayout.ofLeft(
                        TopPos.get(),
                        PageBasicLayout.get(),
                        PageTypeRegistry.displayingPages(DontDisplayPages.get()),
                        TextureMode.get(),
                        IRectangleParam.fromConfigEntry(PageRectangle)
                );
            }
            case MERGE_JEI:{/*todo*/}
            case RIGHT:{
                return AttachedMenuScreenLayout.ofRight(
                        LeftPos.get(),
                        TopPos.get(),
                        PageBasicLayout.get(),
                        PageTypeRegistry.displayingPages(DontDisplayPages.get()),
                        TextureMode.get(),
                        IRectangleParam.fromConfigEntry(PageRectangle)
                );
            }
        }
        return new AttachedMenuScreenLayout.FullCustomizeLayout(
                PageBasicLayout.get(),
                PageTypeRegistry.displayingPages(DontDisplayPages.get()),
                PageSwitchBar.get(),
                LeftPos.get(), TopPos.get(),
                TextureMode.get(),
                IRectangleParam.fromConfigEntry(PageRectangle),
                IRectangleParam.fromConfigEntry(SearchBoxParam),
                IRectangleParam.fromConfigEntry(SortBoxParam),
                IRectangleParam.fromConfigEntry(ConfigButtonParam),
                IRectangleParam.fromConfigEntry(ReverseSortButtonParam)
        );
    }
    
    @Override
    public void set(AttachedMenuScreenLayout attachedMenuScreenLayout) {
        freezeFieldSaver();
        BasicLayout.set(attachedMenuScreenLayout.basicLayout);
        PageBasicLayout.set(attachedMenuScreenLayout.pageParam);
        DontDisplayPages.set(PageTypeRegistry.dontDisplayPages(attachedMenuScreenLayout.pages()));
        PageSwitchBar.set(attachedMenuScreenLayout.pageSwitchBarParam);
        LeftPos.set(attachedMenuScreenLayout.leftPos());
        TopPos.set(attachedMenuScreenLayout.topPos());
        TextureMode.set(attachedMenuScreenLayout.textureMode());
        PageRectangle.set(IRectangleParam.toList(attachedMenuScreenLayout.pageRectangleParam));
        SearchBoxParam.set(IRectangleParam.toList(attachedMenuScreenLayout.searchBoxParam));
        SortBoxParam.set(IRectangleParam.toList(attachedMenuScreenLayout.sortBoxParam));
        ConfigButtonParam.set(IRectangleParam.toList(attachedMenuScreenLayout.configButtonParam));
        ReverseSortButtonParam.set(IRectangleParam.toList(attachedMenuScreenLayout.reverseSortButtonParam));
        unfreezeFieldSaver();
        save();
    }

    @Override
    public ConfigEntryImpl<?>[] fields() {
        return new ConfigEntryImpl[]{
                BasicLayout,
                LeftPos, TopPos,
                PageBasicLayout,
                DontDisplayPages,
                TextureMode,
                PageRectangle,
                PageSwitchBar,
                SearchBoxParam, SortBoxParam, ReverseSortButtonParam, ConfigButtonParam
        };
    }
}
