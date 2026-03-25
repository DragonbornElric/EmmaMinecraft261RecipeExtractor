package com.emma.endinv.options.config;

/**
 * For enum class
 */
public interface EntryPresentable {

    default String key(){
        if(this instanceof Enum<?> e) return e.name();
        throw new UnsupportedOperationException("Interface EntryPresentable only for enum class default");
    }

    /**
     * Description of this enum entry
     */
    String description();

    static <E extends Enum<E> & EntryPresentable> String[] generateComments(Class<E> clazz, String basicComment){
        E[] values = clazz.getEnumConstants();
        String[] out = new String[values.length + 1];
        out[0] = basicComment + " Values:";
        for(int i = 0; i < values.length; i++){
            out[i + 1] = values[i].name() + ": " + values[i].description();
        }
        return out;
    }
}
