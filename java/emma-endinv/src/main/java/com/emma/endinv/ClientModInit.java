package com.emma.endinv;

import com.emma.endinv.AbstractClientModInitializer;
import com.emma.endinv.client.IContainerScreenHelper;
import com.emma.endinv.client.IInputHandler;
import com.emma.endinv.client.KeyMappings;
import com.emma.endinv.client.option.ClientConfigs;
import com.emma.endinv.options.config.json.JsonConfigurationHandler;
import com.emma.endinv.client.events.ClientEvents;
import com.emma.endinv.mixin.AbstractContainerScreenAccessor;
import com.emma.endinv.network.FabricClientNetworking;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.jetbrains.annotations.Nullable;

import static com.emma.endinv.client.KeyMappings.*;

public class ClientModInit extends AbstractClientModInitializer implements ClientModInitializer {
    @Nullable
    private static JsonConfigurationHandler CLIENT_CONFIGS;

    public ClientModInit(){
        super();
        AbstractClientModInitializer.ENDINV_CLIENT = this;
    }

    @Override
    public void onInitializeClient() {
        KeyMappingHelper.registerKeyMapping(KEY_MAPPING_MAP.get(OPEN_MENU));
        KeyMappingHelper.registerKeyMapping(KEY_MAPPING_MAP.get(QUICK_MOVE));//it may be unchangeable
        KeyMappingHelper.registerKeyMapping(KEY_MAPPING_MAP.get(STAR_ITEM_ALTER));
        initClientConfigs();
        FabricClientNetworking.init();
        ClientEvents.register();
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (CLIENT_CONFIGS != null) CLIENT_CONFIGS.save();
        });
    }

    @Override
    protected void regKeyParam(KeyMappings.KeyParam key) {
        KeyMapping mapping = new KeyMapping(key.key(), key.type(), key.keyCode(), key.category());
        KEY_MAPPING_MAP.put(key, mapping);
    }

    @Override
    protected IInputHandler getInputHandler() {
        return new IInputHandler() {
            @Override
            public boolean isActiveAndMatches(KeyParam keyParam, InputWithModifiers input) {
                AbstractClientModInitializer modClient = AbstractClientModInitializer.ENDINV_CLIENT;
                if(modClient == null){
                    throw new IllegalStateException("Client mod not initialized");
                }
                if(!keyParam.condition().isActive()) return false;
                if(!keyParam.modifier().matchesModifier(input)) return false;
                //fabric hot fix
                if(input instanceof  MouseButtonEvent buttonEvent
                        && keyParam.keyCode() == buttonEvent.button()
                        && keyParam.modifier().matchesModifier(input)
                ) return true;
                var reg = modClient.KEY_MAPPING_MAP.get(keyParam);
                return switch (input){
                    case KeyEvent keyEvent -> reg.matches(keyEvent);
                    case MouseButtonEvent buttonEvent -> reg.matchesMouse(buttonEvent);
                    default -> false;
                };
            }
        };
    }

    protected void initClientConfigs() {
        CLIENT_CONFIGS = new JsonConfigurationHandler(
                net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("endless_inventory-client.json"),
                ClientConfigs.getConfigs()
        );
        CLIENT_CONFIGS.load();
    }

    @Override
    protected IContainerScreenHelper getScreenHelper() {
        return new IContainerScreenHelper() {
            @Override
            public int getGuiLeft(AbstractContainerScreen<?> screen) {
                return ((AbstractContainerScreenAccessor) screen).endinv$getLeftPos();
            }

            @Override
            public int getGuiTop(AbstractContainerScreen<?> screen) {
                return ((AbstractContainerScreenAccessor) screen).endinv$getTopPos();
            }

            @Override
            public int getGuiXSize(AbstractContainerScreen<?> screen) {
                return ((AbstractContainerScreenAccessor) screen).endinv$getImageWidth();
            }

            @Override
            public int getGuiYSize(AbstractContainerScreen<?> screen) {
                return ((AbstractContainerScreenAccessor) screen).endinv$getImageHeight();
            }
        };
    }
}
