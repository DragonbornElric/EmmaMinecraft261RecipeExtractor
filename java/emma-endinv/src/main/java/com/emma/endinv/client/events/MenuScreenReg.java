package com.emma.endinv.client.events;

import com.emma.endinv.ModRegistries;
import com.emma.endinv.client.gui.EndlessInventoryScreen;
import net.minecraft.client.gui.screens.MenuScreens;

public final class MenuScreenReg {

    private MenuScreenReg() {
    }

    public static void register() {
        MenuScreens.register(ModRegistries.Menus.getEndInvMenuType(), EndlessInventoryScreen::new);
    }
}
