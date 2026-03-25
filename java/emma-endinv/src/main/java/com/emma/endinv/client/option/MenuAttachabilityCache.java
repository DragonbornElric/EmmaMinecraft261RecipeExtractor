package com.emma.endinv.client.option;

import com.emma.endinv.network.payloads.toClient.MenuAttachabilityPayload;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Client-side cache for server-provided menu attachability rules.
 */
public final class MenuAttachabilityCache {

    private static boolean defaultAttach = true;
    private static boolean inventoryAttach = true;
    private static final Map<MenuType<?>, Boolean> perMenu = new HashMap<>();

    private MenuAttachabilityCache() {}

    public static void accept(MenuAttachabilityPayload payload) {
        defaultAttach = payload.defaultAttach();
        inventoryAttach = payload.inventoryAttach();
        perMenu.clear();
        perMenu.putAll(payload.perMenu());
    }

    public static boolean isAttachable(AbstractContainerScreen<?> screen) {
        AbstractContainerMenu menu = screen.getMenu();
        MenuType<?> type;
        try {
            type = menu.getType();
        }catch (Exception e){
            type = null;
        }
        if (type == null) {
            return inventoryAttach; // inventory special-case
        }

        Boolean v = perMenu.get(type);
        return v != null ? v : defaultAttach;
    }

    public static Map<MenuType<?>, Boolean> snapshot() {
        return Collections.unmodifiableMap(perMenu);
    }
}

