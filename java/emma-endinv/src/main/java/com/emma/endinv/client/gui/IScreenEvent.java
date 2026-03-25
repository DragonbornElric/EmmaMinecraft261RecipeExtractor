package com.emma.endinv.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

public interface IScreenEvent {

    default void addListener(AbstractWidget widget){}

    default MouseButtonEvent getMouseButtonEvent(){return null;}

    default KeyEvent getKeyEvent(){return null;}

    default CharacterEvent getCharEvent(){return null;}

    default double getMouseX(){
        return 0;
    }

    default double getMouseY(){
        return 0;
    }

    default GuiGraphicsExtractor getGuiGraphicsExtractor(){
        return null;
    }

    default float getPartialTick(){
        return 0;
    }

    default int getButton(){
        return -1;
    }

    default int getMouseButton(){
        return -1;
    }

    default void setCanceled(boolean canceled){}

    default double getDragX(){
        return 0;
    }

    default double getDragY(){
        return 0;
    }


    default double getScrollDeltaY(){
        return 0;
    }

    default double getScrollDeltaX(){
        return 0;
    }

    default int getKeyCode(){
        return -1;
    }

    default int getScanCode() {
        return -1;
    }

    default int getModifiers(){
        return 0;
    }

    default char getCodePoint(){
        return 0;
    }
}
