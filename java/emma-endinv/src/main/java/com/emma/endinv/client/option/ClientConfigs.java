package com.emma.endinv.client.option;

import com.emma.endinv.options.SpecifiedMenuAttachingConfig;
import com.emma.endinv.options.config.ConfigEntryImpl;
import com.emma.endinv.options.config.IConfigValue;

import java.util.ArrayList;
import java.util.List;

public final class ClientConfigs {

    private ClientConfigs() {
    }

    private static final List<ConfigEntryImpl<?>> configs = new ArrayList<>();

    public static List<ConfigEntryImpl<?>> getConfigs() {
        return configs;
    }

    private static <T extends IConfigValue<?>> T register(T configEntry) {
        if (configEntry instanceof ConfigEntryImpl<?> entry) {
            configs.add(entry);
        }
        return configEntry;
    }

    public static final ConfigEntryImpl.BooleanEntry DO_ATTACH = register(new ConfigEntryImpl.BooleanEntry(
            "DoAttach", new String[]{"Enable endinv's screen to attach by other menus."}, true));
    public static final SpecifiedMenuAttachingConfig.Entry SPECIFIED_ATTACHABILITY = register(SpecifiedMenuAttachingConfig.Entry.INSTANCE);
    public static final AttachedMenuConfig ATTACHED_MENU_CONFIG = register(new AttachedMenuConfig(
            "AttachedMenuOptions", new String[]{"The attached screen (endinv beside main menu) options"}));
    public static final EIMConfig EIM_CONFIG = register(new EIMConfig(
            "EIMOptions", new String[]{"The endinv's main screen options"}
    ));
    public static final ConfigEntryImpl.BooleanEntry SCREEN_DEBUG = register(new ConfigEntryImpl.BooleanEntry(
            "ScreenDebug", new String[]{"Enable screen debug mode."}, false));
    public static final ConfigEntryImpl.BooleanEntry AUTO_PICK = register(new ConfigEntryImpl.BooleanEntry(
            "AutoPick", new String[]{"Enable auto pick mode."}, true));
}