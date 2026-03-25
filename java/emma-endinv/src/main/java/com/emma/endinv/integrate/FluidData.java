package com.emma.endinv.integrate;

import net.minecraft.core.component.DataComponentPatch;

import org.jetbrains.annotations.Nullable;
import java.util.Objects;

/**
 *
 * @param id to get the fluid instance by id (in registration)
 * @param amountMB i bucket = 1000mb
 */
public record FluidData(String id, long amountMB, @Nullable DataComponentPatch component) implements IFluidStack {

    public static final FluidData EMPTY = new FluidData("air",0,null);

    @Override
    public boolean isEmpty(){
        return Objects.equals(id, "air") || amountMB==0;
    }

    public FluidData accumulate(long amountMB){
        return new FluidData(id,this.amountMB+amountMB, component);
    }

    public boolean isSame(FluidData other){
        return this.id.equals(other.id) && Objects.equals(this.component, other.component);
    }

    /** Will ignore other.component, check via {@link #isSame(FluidData)}
     */
    public FluidData accumulate(FluidData other){
        return new FluidData(id,this.amountMB + other.amountMB, component);
    }
}
