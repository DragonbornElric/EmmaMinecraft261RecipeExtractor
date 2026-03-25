package com.emma.endinv.client.option;

import com.emma.endinv.client.gui.bg.IRectangleParam;
import com.emma.endinv.client.gui.bg.ScreenRectangleWidgetParam;
import com.emma.endinv.menu.page.PageType;
import com.emma.endinv.menu.page.PageTypeRegistry;
import com.emma.endinv.options.config.ComplexConfigEntryImpl;
import com.emma.endinv.options.config.ConfigEntryImpl;
import net.minecraft.client.Minecraft;

import java.util.List;

public class EIMConfig extends ComplexConfigEntryImpl<EIMConfig.Param> {

    public static final EIMConfig INSTANCE = new EIMConfig("EndlessInventoryMenuConfig", new String[]{""});

    public final IntEntry Rows = new IntEntry(
            "rows", new String[]{"The default rows of page, if auto suit rows, it's the max row count."}, 9, 0, 255);
    public final ListEntry<String> DontDisplayPages = new ListEntry<>(
            "HidePages",
            new String[]{"The pages will not have a tab in the page switch bar to be switched to"},
            List.of()
    );
    public final PageSwitchBarConfig PageSwitchBar = PageSwitchBarConfig.INSTANCE;

    public final ListEntry<Integer> SearchBoxParam = IRectangleParam.createRectangleConfigEntry(
            "SearchBoxParam", new String[]{"The search box parameters"}, Param.DEFAULT.searchBoxParam);
    public final ListEntry<Integer> SortBoxParam = IRectangleParam.createRectangleConfigEntry(
            "SortBoxParam", new String[]{"The sort box parameters"}, Param.DEFAULT.sortBoxParam);
    public final ListEntry<Integer> ReverseSortButtonParam = IRectangleParam.createRectangleConfigEntry(
            "ReverseSortButtonParam", new String[]{"The reverse sort button parameters"}, Param.DEFAULT.reverseSortButtonParam);
    public final ListEntry<Integer> ConfigButtonParam = IRectangleParam.createRectangleConfigEntry(
            "ConfigButtonParam", new String[]{"The config button parameters"}, Param.DEFAULT.configButtonParam);

    public EIMConfig(String key, String[] comments) {
        super(key, comments, Param.DEFAULT);
    }

    /**
     * The getter of a complex config entry may be null.
     * Let it return {@link #fields()}'s returns.
     * <br>
     * If C is a record, let it return a new instance of C and invoke all fields' getters.
     *
     * @return the default value of the complex config entry.
     */
    @Override
    public Param get() {
        return new Param(
                Rows.get(),
                -1,-1,
                PageTypeRegistry.displayingPages(DontDisplayPages.get()),
                PageSwitchBar.get(),
                IRectangleParam.fromConfigEntry(SearchBoxParam),
                IRectangleParam.fromConfigEntry(SortBoxParam),
                IRectangleParam.fromConfigEntry(ConfigButtonParam),
                IRectangleParam.fromConfigEntry(ReverseSortButtonParam)
        );
    }

    /**
     * The setter of a complex config entry may be null.
     * Let it set every field of {@link #fields()}.
     *
     * @param param to be used to set fields.
     */
    @Override
    public void set(Param param) {
        freezeFieldSaver();
        Rows.set(param.rows());
        DontDisplayPages.set(PageTypeRegistry.dontDisplayPages(param.pages()));
        PageSwitchBar.set(param.pageSwitchBarConfig);
        SearchBoxParam.set(IRectangleParam.toList(param.searchBoxParam));
        SortBoxParam.set(IRectangleParam.toList(param.sortBoxParam));
        ConfigButtonParam.set(IRectangleParam.toList(param.configButtonParam));
        ReverseSortButtonParam.set(IRectangleParam.toList(param.reverseSortButtonParam));
        unfreezeFieldSaver();
        save();
    }

    @Override
    public ConfigEntryImpl<?>[] fields() {
        return new ConfigEntryImpl[]{
                Rows, DontDisplayPages, PageSwitchBar, SearchBoxParam, SortBoxParam, ReverseSortButtonParam, ConfigButtonParam
        };
    }

    public record Param(int rows,
                        int leftPos, int topPos,// negative for auto suit
                        List<PageType> pages,
                        PageSwitchBarConfig.Param pageSwitchBarConfig,
                        IRectangleParam searchBoxParam,
                        IRectangleParam sortBoxParam,
                        IRectangleParam configButtonParam,
                        IRectangleParam reverseSortButtonParam
    )implements SFParamProvider{
        public static final Param DEFAULT = new Param(6,-1,-1,
                PageTypeRegistry.getDisplayPages(),
                PageSwitchBarConfig.Param.DEFAULT,
                new ScreenRectangleWidgetParam(/*this.leftPos + */89, /*this.topPos + */5, 80, 12),
                new ScreenRectangleWidgetParam/*offset*/(8, 5, 60, 12),
                new ScreenRectangleWidgetParam(
                        /*this.leftPos + this.imageWidth*/0,
                        /*Math.min(this.topPos + this.imageHeight, screen.height - 20)*/-20,
                        18, 18),
                new ScreenRectangleWidgetParam/*offset*/(70,5,12,12)
        );

        public Param adjust(){
            int screenHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();
            int screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
            return new Param(
                    rows,
                    (screenWidth - 176) / 2,
                    (screenHeight - 114 - rows*18) / 2,
                    pages,
                    pageSwitchBarConfig,
                    searchBoxParam,
                    sortBoxParam,
                    new ScreenRectangleWidgetParam(
                            configButtonParam.x() <= 0 ? 176 + configButtonParam.x() : configButtonParam.x(),
                            configButtonParam.y() <= 0 ? Math.min(114 + rows * 18, screenHeight + configButtonParam.y() - leftPos) : configButtonParam.y(),
                            configButtonParam.width(),
                            configButtonParam.height()),
                    reverseSortButtonParam
            );
        }

        @Override
        public int columns() {
            return 9;
        }

        @Override
        public TextureMode textureMode() {
            return TextureMode.FROM_RESOURCE;
        }

        @Override
        public int pageTabCount() {
            return pageSwitchBarConfig.maxBars();
        }

        @Override
        public IRectangleParam pageParamAdjusted() {
            return new ScreenRectangleWidgetParam(leftPos, topPos, 9*18 + 8 +8, 18*rows + 17 + 12);
        }

        @Override
        public IRectangleParam pageTabParamAdjusted() {
            return pageSwitchBarConfig.tabParam().applyOffset(leftPos, topPos);
        }

        @Override
        public IRectangleParam pageTabDecA() {
            return pageSwitchBarConfig.buttonDec().applyOffset(leftPos, topPos);
        }

        @Override
        public IRectangleParam pageTabIncA() {
            return pageSwitchBarConfig.buttonInc().applyOffset(leftPos, topPos);
        }

        @Override
        public IRectangleParam sortBoxA() {
            return sortBoxParam.applyOffset(leftPos, topPos);
        }

        @Override
        public IRectangleParam configButtonA() {
            return configButtonParam.applyOffset(leftPos, topPos);
        }

        @Override
        public IRectangleParam reverseSortButtonA() {
            return reverseSortButtonParam.applyOffset(leftPos, topPos);
        }

        @Override
        public IRectangleParam searchBoxA() {
            return searchBoxParam.applyOffset(leftPos, topPos);
        }
    }
}
