package com.emma.endinv.client.option;

import com.emma.endinv.options.config.EntryPresentable;

public enum TextureMode implements EntryPresentable {

    FROM_RESOURCE("The menu will be rigged up with Minecraft's other gui textures"),
    TRANSPARENT("A simple transparent view."),
    DEDICATED_LOCATION("Can customize the texture of menu, mod's assets has shown the file path.");

    private final String description;

    TextureMode(String description) {
        this.description = description;
    }

    /**
     * Description of this enum entry
     */
    @Override
    public String description() {
        return description;
    }
}
