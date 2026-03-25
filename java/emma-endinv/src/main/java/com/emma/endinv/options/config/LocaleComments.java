package com.emma.endinv.options.config;

import net.minecraft.network.chat.Component;

import java.util.function.Supplier;

/**
 * Wrap String as a translation key of Minecraft's locale.
 * Try finding a translation based on the user's language for every String in mc lang assets.
 * Return translated Strings if found translation or original Strings if not.
 */
public class LocaleComments implements Supplier<String[]> {

    private final String[] comments;

    public LocaleComments(String[] comments){
        this.comments = comments;
    }

    @Override
    public String[] get() {
        if (this.comments == null || this.comments.length == 0) {
            return new String[0];
        }
        String[] result = new String[this.comments.length];
        for (int i = 0; i < this.comments.length; i++) {
            String key = this.comments[i];
            if (key == null) {
                result[i] = null;
            } else {
                result[i] = Component.translatableWithFallback(key, key).getString();
            }
        }
        return result;
    }

    public Component[] getTranslatedComments(){
        if (this.comments == null || this.comments.length == 0) {
            return new Component[0];
        }
        Component[] result = new Component[this.comments.length];
        for (int i = 0; i < this.comments.length; i++) {
            String key = this.comments[i];
            result[i] = key == null ? Component.empty() : Component.translatableWithFallback(key, key);
        }
        return result;
    }
}
