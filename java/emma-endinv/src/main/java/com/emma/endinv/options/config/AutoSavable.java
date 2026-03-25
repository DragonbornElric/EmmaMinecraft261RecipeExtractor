package com.emma.endinv.options.config;

public interface AutoSavable<T> extends IConfigValue<T>{

    void save();

    /**@implNote should invoke {@link #save()}
     * <pre>
     * e.g.,
     * set(T t){
     *     **original set func**
     *     save(); //invoke save()
     * }
     * </pre>
     */
    void set(T t);

    /**@implNote deal {@link #set} as setAndSave <br>
     *<pre>
     *setAndSave(T t){
     *   set(t);
     *   ///save(); set() method auto saves value
     * }
     *</pre>
     */
    default void setAndSave(T t){
        set(t);
        /// save()
    }
}
