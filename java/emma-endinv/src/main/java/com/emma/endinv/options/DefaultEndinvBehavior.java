package com.emma.endinv.options;

import com.emma.endinv.options.config.ComplexConfigEntryImpl;
import com.emma.endinv.options.config.ConfigEntryImpl;
import com.emma.endinv.util.Accessibility;

/**Config for default behaviors of an Endless Inventory
 * This class is more likely ConfigEntries' present <p>
 * Use via {code Behavior.Field.get()/set()}<br>
 * It's unrecommended to use {@link #get()} or {@link #set(Present)} directly.
 */
public class DefaultEndinvBehavior extends ComplexConfigEntryImpl<DefaultEndinvBehavior.Present> {

    private static final String COMMENT_INFINITY = "when item count reaches max stack count, this item will be served as infinity";
    private static final String[] COMMENT_TRANSFERMODE = new String[]{"Controls how server Endless Inventory transfer contents to client",
            "Receive: "+ ContentTransferMode.ALL.name()+", "+ContentTransferMode.PART.name()};
    private static final String[] COMMENT_ACCESS = new String[]{"Controls how players can access Endless Inventory, options:",
            "PUBLIC : all players can access",
            "RESTRICTED : only owner and allowed can access",
            "PRIVATE : only owner can access"};

    public static final DefaultEndinvBehavior INSTANCE = new DefaultEndinvBehavior("defaultEndinvBehavior", new String[]{"Default behavior of Endless Inventory"}, Present.DEFAULT);

    public final BooleanEntry EnableInfinity = new BooleanEntry("EnableInfinity", new String[]{COMMENT_INFINITY}, false);
    public final IntEntry MaxStackSize = new IntEntry("MaxStackSize", new String[]{"Max stack size(count) of one item, default to 2147483647"}, Integer.MAX_VALUE);
    public final EnumEntry<ContentTransferMode> TransferMode = new EnumEntry<>("ContentTransferMode", COMMENT_TRANSFERMODE, ContentTransferMode.ALL);
    public final EnumEntry<Accessibility> Access = new EnumEntry<>("Accessibility", COMMENT_ACCESS, Accessibility.PUBLIC);


    public record Present(boolean infinityMode, int maxStackSize, ContentTransferMode transferMode, Accessibility accessibility) {
        static Present DEFAULT = new Present(false, Integer.MAX_VALUE, ContentTransferMode.ALL, Accessibility.PUBLIC);
    }

    public DefaultEndinvBehavior(String key, String[] comments, Present defaultValue) {
        super(key, comments, defaultValue);
    }

    @Override
    public ConfigEntryImpl<?>[] fields() {
        return new ConfigEntryImpl[]{EnableInfinity,MaxStackSize,TransferMode,Access};
    }

    @Override
    public Present defaultValue() {
        return Present.DEFAULT;
    }

    @Override
    public Present get() {
        if(!isInitialized() && this.checkInitializationStrict){
            throw new IllegalStateException("ConfigEntries are not initialized completely yet!");
        }
        return new Present(EnableInfinity.get(),MaxStackSize.get(),TransferMode.get(),Access.get());
    }

    @Override
    public void set(Present defaultEndinvBehavior) {
        if(!isInitialized() && this.checkInitializationStrict){
            throw new IllegalStateException("ConfigEntries are not initialized completely yet!");
        }
        freezeFieldSaver();
        EnableInfinity.set(defaultEndinvBehavior.infinityMode());
        MaxStackSize.set(defaultEndinvBehavior.maxStackSize());
        TransferMode.set(defaultEndinvBehavior.transferMode());
        Access.set(defaultEndinvBehavior.accessibility());
        unfreezeFieldSaver();
        save();
    }
}
