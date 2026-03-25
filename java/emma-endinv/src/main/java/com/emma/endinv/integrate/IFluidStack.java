package com.emma.endinv.integrate;

import net.minecraft.core.component.DataComponentPatch;

import java.util.Objects;

public interface IFluidStack {
    default boolean isEmpty(){
        return amountMB() == 0 || Objects.equals(id() , "air");
    }

    String id();

    long amountMB();

    DataComponentPatch component();
}
