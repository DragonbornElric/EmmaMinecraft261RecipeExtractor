package com.emma.endinv.client.events;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.CharacterEvent;

public final class ScreenCharTypedEvents {

    public static final Event<BeforeCharTyped> BEFORE_CHAR_TYPED = EventFactory.createArrayBacked(
        BeforeCharTyped.class,
        listeners -> (guiEventListener, event) -> {
            for (BeforeCharTyped listener : listeners) {
                if (listener.beforeCharTyped(guiEventListener, event)) {
                    return true;
                }
            }
            return false;
        }
    );

    @FunctionalInterface
    public interface BeforeCharTyped {
        boolean beforeCharTyped(GuiEventListener guiEventListener, CharacterEvent event);
    }

    private ScreenCharTypedEvents() {
    }
}
