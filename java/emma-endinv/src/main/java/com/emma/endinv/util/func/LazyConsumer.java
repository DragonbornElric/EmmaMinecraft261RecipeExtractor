package com.emma.endinv.util.func;

import java.util.function.Consumer;

public class LazyConsumer<T> implements Consumer<T> {

    private T cache;
    private final Consumer<T> consumer;

    public LazyConsumer(Consumer<T> consumer) {
        this.consumer = consumer;
    }

    /**
     * Performs this operation on the given argument.
     *
     * @param t the input argument
     */
    @Override
    public void accept(T t) {
        if(cache==null || !cache.equals(t)){
            consumer.accept(t);
            cache = t;
        }
    }
}
