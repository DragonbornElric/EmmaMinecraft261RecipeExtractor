package com.emma.endinv;

import com.emma.endinv.item.ScreenDebugger;
import com.emma.endinv.item.TestEndInv;
import com.emma.endinv.menu.EndlessInventoryMenu;
import com.emma.endinv.menu.page.PageType;
import com.emma.endinv.network.IPacketDistributor;
import com.emma.endinv.network.payloads.SyncedConfig;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.function.Supplier;


public abstract class AbstractModInitializer {

    public static final ResourceKey<@NotNull Registry<@NotNull PageType>> PAGE_REG_KEY = ResourceKey.createRegistryKey(withModLocation("display_page"));

    @FunctionalInterface
    public interface RegistryCallback<T> {
        <R extends T> Supplier<R> register(String id, Supplier<R> supplier);
    }

    public static Identifier withModLocation(String id){
        return Identifier.fromNamespaceAndPath(ModInfo.MOD_ID, id);
    }

    protected AbstractModInitializer(){}

    protected void init(){
        registerItems(itemReg());
        registerMenuType(menuReg());
        registerNbtAttachment();
        loadServerConfig();
        ModInfo.setPacketDistributor(loadPacketDistributor());
        ModInfo.platformContext = loadOtherPlatformSpecific();
    }

    private void registerItems(RegistryCallback<Item> method){
        ModRegistries.Items.testEndInv = method.register("endinv_accessor",
                ()->new TestEndInv(new Item.Properties().setId(ResourceKey.create(Registries.ITEM, withModLocation("endinv_accessor")))));
        ModRegistries.Items.screenDebugger = method.register("screen_debugger",
                ()->new ScreenDebugger(new Item.Properties().setId(ResourceKey.create(Registries.ITEM, withModLocation("screen_debugger")))));
    }

    private void registerMenuType(RegistryCallback<MenuType<?>> method){
        ModRegistries.Menus.endinvMenuType = method.register("endinv_menu",createEndInvMenuType());
    }

    private void registerNbtAttachment(){
        ModRegistries.NbtAttachments.endInvUUID = createEndInvUUID("endinv_uuid");
        ModRegistries.NbtAttachments.syncedConfig = createSyncedConfig("endinv_settings");
    }

    protected abstract IPlatform loadOtherPlatformSpecific();

    protected abstract IPacketDistributor loadPacketDistributor();

    protected abstract void loadServerConfig();

    protected abstract RegistryCallback<Item> itemReg();

    protected abstract RegistryCallback<MenuType<?>> menuReg();

    protected abstract Supplier<MenuType<EndlessInventoryMenu>> createEndInvMenuType();

    protected abstract NbtAttachment<UUID> createEndInvUUID(String name);

    protected abstract NbtAttachment<SyncedConfig> createSyncedConfig(String name);

}
