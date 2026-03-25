package com.emma.endinv.client;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

public interface IContainerScreenHelper{

    int getGuiLeft(AbstractContainerScreen<?> screen);

    int getGuiTop(AbstractContainerScreen<?> screen);

    int getGuiXSize(AbstractContainerScreen<?> screen);

    int getGuiYSize(AbstractContainerScreen<?> screen);
}
