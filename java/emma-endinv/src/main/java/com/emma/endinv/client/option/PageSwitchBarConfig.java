package com.emma.endinv.client.option;


import com.emma.endinv.client.gui.bg.IRectangleParam;
import com.emma.endinv.client.gui.bg.ScreenRectangleWidgetParam;
import com.emma.endinv.options.config.ComplexConfigEntryImpl;
import com.emma.endinv.options.config.ConfigEntryImpl;

public class PageSwitchBarConfig extends ComplexConfigEntryImpl<PageSwitchBarConfig.Param> {
    
    public static final PageSwitchBarConfig INSTANCE = new PageSwitchBarConfig("PageSwitchBar", new String[]{"PageSwitchBar Options"}, Param.DEFAULT);


    public final IntEntry MaxBars = new IntEntry(
            "maxBars", new String[]{"Maximum number of page tabs shown (-1 for auto)"}, 255, -1, Integer.MAX_VALUE);
    public final ListEntry<Integer> TabParam = IRectangleParam.createRectangleConfigEntry(
            "tabParam", new String[]{"Page tab button parameters"}, new ScreenRectangleWidgetParam(-32, 0, 32, 28));
    public final BooleanEntry DirectionIsVertical = new BooleanEntry(
            "direction", new String[]{"True for vertical layout, false for horizontal layout"}, true);
    public final ListEntry<Integer> ButtonDec = IRectangleParam.createRectangleConfigEntry(
            "buttonDec", new String[]{"Decrease button parameters"}, new ScreenRectangleWidgetParam(-20, -20, 20, 14));
    public final ListEntry<Integer> ButtonInc = IRectangleParam.createRectangleConfigEntry(
            "buttonInc", new String[]{"Increase button parameters"}, new ScreenRectangleWidgetParam(-20, -20, 20, 14));

    public PageSwitchBarConfig(String key, String[] comments, Param defaultValue) {
        super(key, comments, defaultValue);
    }

    /**
     * The getter of a complex config entry may be null.
     * Let it return {@link #fields()}'s returns.
     *
     * @return the default value of the complex config entry.
     */
    @Override
    public Param get() {
        return new Param(
                MaxBars.get(),
                IRectangleParam.fromConfigEntry(TabParam),
                DirectionIsVertical.get(),
                IRectangleParam.fromConfigEntry(ButtonDec),
                IRectangleParam.fromConfigEntry(ButtonInc)
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
        MaxBars.set(param.maxBars());
        TabParam.set(param.tabParam().toList());
        DirectionIsVertical.set(param.direction_isVertical());
        ButtonDec.set(param.buttonDec().toList());
        ButtonInc.set(param.buttonInc().toList());
    }

    @Override
    public ConfigEntryImpl<?>[] fields() {
        return new ConfigEntryImpl[]{
                MaxBars,
                TabParam,
                DirectionIsVertical,
                ButtonDec,
                ButtonInc
        };
    }

    /**
     * The param of Endinv's page switch bar.
     * @param maxBars negative for auto adjust
     * @param tabParam the param of a tab that switches pages. The {@code x,y} is the OFFSET by the EIM/Attached Screen.
     * @param direction_isVertical false for a horizontal layout of the bar. TODO impl this
     * @param buttonDec the param of a button that scrolls up/left to show low-index page tabs,
     *                 this decreases {@link com.emma.endinv.client.gui.ScreenFramework#firstPageIndex} field.
     *                  The {@code x,y} is the OFFSET by the EIM/Attached Screen.
     * @param buttonInc the param of a button that scrolls down/right to show high-index page tabs,
     *                  this increases {@link com.emma.endinv.client.gui.ScreenFramework#firstPageIndex} field.
     *                   The {@code x,y} is the OFFSET by the EIM/Attached Screen.
     */
    public record Param(int maxBars,
                        IRectangleParam tabParam,
                        boolean direction_isVertical,
                        IRectangleParam buttonDec,
                        IRectangleParam buttonInc
    ) {
        public static final Param DEFAULT = new Param(
                255,
                new ScreenRectangleWidgetParam(-32, 0, 32, 28),
                true,
                new ScreenRectangleWidgetParam(-20, -20,20,14),
                new ScreenRectangleWidgetParam(-20,-20,20,14)
        );
        public Param applyOffset(int leftPos, int topPos){
            return new Param(maxBars, tabParam.applyOffset(leftPos, topPos), direction_isVertical, buttonDec.applyOffset(leftPos, topPos), buttonInc.applyOffset(leftPos, topPos));
        }

        public int maxLength(){
            return maxBars * tabParam.height();
        }
    }


}
