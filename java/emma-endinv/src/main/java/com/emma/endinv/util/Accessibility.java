package com.emma.endinv.util;


import com.emma.endinv.options.config.EntryPresentable;

public enum Accessibility implements EntryPresentable {

    PUBLIC("All players can access this inventory"),
    RESTRICTED("Only players allowed can access this inventory"),
    PRIVATE("Only owner can access this inventory");

    private final String description;

    Accessibility(String description) {
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
