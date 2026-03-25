package com.emma.endinv.client.events;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class ScreenDebug {

    private static final int OVERLAY_COLOR = 0xAA000000;
    private static final Component HIDE_MESSAGE = Component.literal("[F4]Menu screen rendering stopped!");

    private static boolean hideMenu;

    private ScreenDebug() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> {
                renderOverlay(s, graphics);
                com.emma.endinv.client.ScreenDebug.debugInfo(s, graphics, mouseX, mouseY);
            });

            ScreenKeyboardEvents.afterKeyPress(screen).register((s, event) -> {
                com.emma.endinv.client.ScreenDebug.click(event.key(), s);
                if (event.key() == InputConstants.KEY_F4) {
                    hideMenu = !hideMenu;
                }
            });
        });
    }

    private static void renderOverlay(Screen screen, GuiGraphicsExtractor graphics) {
        if (!hideMenu) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        graphics.fill(0, 0, screen.width, screen.height, OVERLAY_COLOR);
        graphics.text(minecraft.font, HIDE_MESSAGE, 0, 0, 0xFFFFAA00);
    }
}
