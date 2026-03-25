package com.emma.endinv.mixin;

import com.emma.endinv.client.events.ScreenAttachment;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void endinv$mouseDragged(MouseButtonEvent event, double deltaX, double deltaY, CallbackInfoReturnable<Boolean> cir) {
        if (ScreenAttachment.handleMouseDrag((AbstractContainerScreen<?>) (Object) this, event, deltaX, deltaY)) {
            cir.setReturnValue(true);
        }
    }
}
