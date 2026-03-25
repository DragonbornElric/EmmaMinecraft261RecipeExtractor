package com.emma.endinv.mixin;

import com.emma.endinv.client.events.ScreenCharTypedEvents;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.CharacterEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GuiEventListener.class)
public interface GuiEventListenerCharTypedMixin {

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void endinv$beforeCharTyped(CharacterEvent event, CallbackInfoReturnable<Boolean> cir) {
            if (ScreenCharTypedEvents.BEFORE_CHAR_TYPED.invoker().beforeCharTyped((GuiEventListener) this, event)) {
            cir.setReturnValue(true);
            cir.cancel();
        }
    }
}

