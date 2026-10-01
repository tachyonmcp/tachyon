/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates that an annotated element is experimental and may be subject to
 * change or removal in future versions. This annotation serves as a warning
 * to developers that the API is not stable and its behaviour may not be finalized.
 * <p>
 * Use this annotation to mark classes, methods, constructors, fields, or packages
 * that are in an experimental state and should be used with caution.
 * <p>
 * Such elements are typically introduced for testing purposes or early
 * feedback and are not guaranteed to be part of the final API.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({
    ElementType.TYPE,
    ElementType.METHOD,
    ElementType.CONSTRUCTOR,
    ElementType.FIELD,
    ElementType.PACKAGE,
    ElementType.PARAMETER,
    ElementType.RECORD_COMPONENT
})
public @interface ExperimentalApi {

    /**
     * Version in which the annotated API became experimental.
     *
     * @return the version in which this API status began
     */
    String since() default "";
}
