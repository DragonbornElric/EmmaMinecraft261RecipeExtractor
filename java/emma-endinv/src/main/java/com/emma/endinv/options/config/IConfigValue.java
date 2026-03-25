package com.emma.endinv.options.config;

import java.util.function.Consumer;
import java.util.function.Supplier;

public interface IConfigValue<T> {
    T get();

    void set(T t);

    static <T> IConfigValue<T> of(Supplier<T> getter, Consumer<T> setter){
        return new IConfigValue<T>() {
            @Override
            public T get() {
                return getter.get();
            }

            @Override
            public void set(T t) {
                setter.accept(t);
            }
        };
    }

    static <T> IConfigValue<T> of(Supplier<T> getter, Consumer<T> setter, Runnable onset){
        return new IConfigValue<T>() {
            @Override
            public T get() {
                return getter.get();
            }

            @Override
            public void set(T t) {
                setter.accept(t);
                onset.run();
            }
        };
    }
}
