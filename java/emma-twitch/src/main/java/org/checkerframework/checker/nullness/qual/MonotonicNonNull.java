package org.checkerframework.checker.nullness.qual;

import java.lang.annotation.*;

/**
 * Stub annotation replacing the Checker Framework's {@code @MonotonicNonNull}.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.FIELD, ElementType.TYPE_USE})
public @interface MonotonicNonNull {
}
