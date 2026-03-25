package com.emma.endinv.autopick;

import net.minecraft.util.StringRepresentable;

public enum AutopickType implements StringRepresentable {

    MOB_LOOT("mob_loot"),
    BLOCK_DROP("block_drop"),
    ITEM_PICK_INTO("item_pick_into");

    public static final StringRepresentable.EnumCodec<AutopickType> CODEC = StringRepresentable.fromEnum(AutopickType::values);

    private final String name;

    AutopickType(String name){
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
