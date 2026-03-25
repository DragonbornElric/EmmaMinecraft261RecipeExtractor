package com.emma.endinv.client.option;

import com.emma.endinv.options.config.ComplexConfigEntryImpl;
import com.emma.endinv.options.config.ConfigEntryImpl;

/*Left and right mode options*/

/**Controls pages' layout and location in AS.
 *
 */
public class PageBasicLayoutConfig extends ComplexConfigEntryImpl<PageBasicLayoutConfig.Param> {

    public final ConfigEntryImpl<Integer> Rows = new ConfigEntryImpl.IntEntry(
            "rows",
            new String[]{"The default rows of page, if auto suit rows, it's the max row count."},
            15,0,255
    );
    public final ConfigEntryImpl<Integer> Columns = new ConfigEntryImpl.IntEntry(
            "columns",
            new String[]{"The default columns of page, if auto suit columns, it's the max column count."},
            9,0,255
    );
    public final ConfigEntryImpl<Boolean> autoRows = new ConfigEntryImpl.BooleanEntry(
            "auto_rows",
            new String[]{"Auto suit row count based on the GuiSize in case the page exceeded the gui screen."},
            true
    );
    public final ConfigEntryImpl<Boolean> autoColumns = new ConfigEntryImpl.BooleanEntry(
            "auto_columns",
            new String[]{"Auto suit column count based on the GuiSize in case the page covered the menu or exceeded the gui screen."},
            true
    );

    public PageBasicLayoutConfig(String key, String[] comments, Param defaultValue) {
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
        return new Param(Rows.get(), Columns.get(), autoRows.get(), autoColumns.get());
    }

    /**
     * The setter of a complex config entry may be null.
     * Let it set every field of {@link #fields()}.
     *
     * @param param to be used to set fields.
     */
    @Override
    public void set(Param param) {
        Rows.set(param.rows());
        Columns.set(param.columns());
        autoRows.set(param.autoRows());
        autoColumns.set(param.autoColumns());
        save();
    }

    @Override
    public ConfigEntryImpl<?>[] fields() {
        return new ConfigEntryImpl[]{
                Rows, Columns, autoRows, autoColumns
        };
    }

    /** Page's parameters. Both used to present config value or provide as ScreenFrameWork's parameters.
     * @param autoRows when true, the rows will be set to the max row count that can fit the screen. When provided as ScreenFramework's param, it should be false as the row count is decided.
     * @param autoColumns when true, the columns will be set to the max column count that can fit the screen. When provided as ScreenFramework's param, it should be false as the column count is decided.
     */
    public record Param(int rows, int columns, boolean autoRows, boolean autoColumns){
        public static final Param DEFAULT = new Param(15,9,true,true);
    }
}
