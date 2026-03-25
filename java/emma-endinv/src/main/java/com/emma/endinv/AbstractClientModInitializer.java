package com.emma.endinv;

import com.emma.endinv.client.ClientModInfo;
import com.emma.endinv.client.IContainerScreenHelper;
import com.emma.endinv.client.IInputHandler;
import com.emma.endinv.client.KeyMappings;
import net.minecraft.client.KeyMapping;

import org.jetbrains.annotations.Nullable;
import java.util.HashMap;
import java.util.Map;

import static com.emma.endinv.client.KeyMappings.*;

public abstract class AbstractClientModInitializer {
    @Nullable
    public static AbstractClientModInitializer ENDINV_CLIENT;
    public final Map<KeyMappings.KeyParam, KeyMapping> KEY_MAPPING_MAP = new HashMap<>();

    protected AbstractClientModInitializer(){
        ModInfo.clientLoaded = true;
        ClientModInfo.inputHandler = getInputHandler();
        ClientModInfo.containerScreenHelper = getScreenHelper();

        regKeyParam(OPEN_MENU);
        regKeyParam(QUICK_MOVE);
        regKeyParam(STAR_ITEM);
        regKeyParam(STAR_ITEM_ALTER);
    }

    protected abstract void regKeyParam(KeyMappings.KeyParam key);

    protected IInputHandler getInputHandler(){
        return new IInputHandler() {};
    }

    protected abstract IContainerScreenHelper getScreenHelper();

}
