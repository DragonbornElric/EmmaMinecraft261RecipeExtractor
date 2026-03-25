package com.emma.endinv.client;

import com.emma.endinv.AbstractClientModInitializer;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

public interface IInputHandler {

    /// @see net.minecraft.client.input.MouseButtonEvent
    /// @see net.minecraft.client.input.KeyEvent

    default boolean isActiveAndMatches(KeyMappings.KeyParam keyParam, InputWithModifiers input){
        AbstractClientModInitializer modClient = AbstractClientModInitializer.ENDINV_CLIENT;
        if(modClient == null){
            throw new IllegalStateException("Client mod not initialized");
        }
        if(!keyParam.condition().isActive()) return false;
        if(!keyParam.modifier().matchesModifier(input)) return false;
        var reg = modClient.KEY_MAPPING_MAP.get(keyParam);
        return switch (input){
            case KeyEvent keyEvent -> reg.matches(keyEvent);
            case MouseButtonEvent buttonEvent -> reg.matchesMouse(buttonEvent);
            default -> false;
        };
    }

}
