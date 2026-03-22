package com.emma.bridge.catalogue;

public enum ObtainMethod {
    MINE,
    CRAFT_SHAPED_2x2,
    CRAFT_SHAPED_3x3,
    CRAFT_SHAPELESS,
    SMELT,              // Legacy — kept for backward compat with old JSON
    SMELT_FURNACE,
    SMELT_BLAST,
    SMELT_SMOKER,
    SMELT_CAMPFIRE,
    MOB_DROP,
    SHEAR,
    CROP,
    SMITH,
    SMITH_TRIM,
    STONECUTTER,
    TRANSFORM,          // Block/world interaction (tool-on-block, water contact, fluid)
    INTERACT,           // Entity/block right-click (shear, milk, honeycomb)
    CUSTOM,
    BREW_POTION,        // Potion brewing: base potion + ingredient → result potion
    BREW_CONTAINER,     // Container upgrade: potion + ingredient → splash/lingering
    BREW_CONTAINERS,    // Metadata: valid brewing container items (no itemId)
    ENCHANTMENT,        // Metadata: enchantment registry data
    BANNER_PATTERN,     // Metadata: banner pattern registry data
    ITEM_PROPERTIES;    // Metadata: item repair, enchantability, equippable, durability

    public boolean isCraftType() {
        return this == CRAFT_SHAPED_2x2 || this == CRAFT_SHAPED_3x3 || this == CRAFT_SHAPELESS;
    }

    public boolean isSmeltType() {
        return this == SMELT || this == SMELT_FURNACE || this == SMELT_BLAST
                || this == SMELT_SMOKER || this == SMELT_CAMPFIRE;
    }

    public boolean isBrewType() {
        return this == BREW_POTION || this == BREW_CONTAINER;
    }

    /** True if this is a metadata-only entry type (no direct acquisition path). */
    public boolean isMetadataOnly() {
        return this == BREW_CONTAINERS || this == ENCHANTMENT
                || this == BANNER_PATTERN || this == ITEM_PROPERTIES;
    }
}
