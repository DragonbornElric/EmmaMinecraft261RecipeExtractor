package com.emma.endinv.util;


import java.lang.annotation.*;

/**A field annotated with NotNullWhenInitialized claims
 *  that it asserts self is not {@code null} after initialization.
 *
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.FIELD})
public @interface NotNullWhenInitialized{
    /// @see org.jspecify.annotations.NullMarked
    /// @see org.jetbrains.annotations.NotNull
}
