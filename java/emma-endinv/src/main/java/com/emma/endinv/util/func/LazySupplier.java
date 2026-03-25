package com.emma.endinv.util.func;

import java.util.function.Supplier;

public class LazySupplier<T> implements Supplier<T> {

    private T cache;
    private final Supplier<T> supplier;

    public LazySupplier(Supplier<T> supplier) {
        this.supplier = supplier;
    }


    /**
     * Gets a result.
     *
     * @return a result
     */
    @Override
    public T get() {
        if(cache==null){
            cache = supplier.get();
        }
        return cache;
    }
}
