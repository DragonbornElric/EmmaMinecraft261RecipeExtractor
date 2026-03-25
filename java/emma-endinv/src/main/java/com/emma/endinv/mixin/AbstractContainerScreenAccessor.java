package com.emma.endinv.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {

    @Accessor("leftPos")
    int endinv$getLeftPos();

    @Accessor("topPos")
    int endinv$getTopPos();

    @Accessor("imageWidth")
    int endinv$getImageWidth();

    @Accessor("imageHeight")
    int endinv$getImageHeight();
}
