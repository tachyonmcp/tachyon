/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a public type, member, or package that exists only for Tachyon's own cross-package
 * wiring. It is NOT a stability contract: it may change or be removed in any release without
 * notice. Do not depend on it from application code.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD, ElementType.PACKAGE})
public @interface InternalApi {

    /**
     * Version in which the annotated API became internal.
     *
     * @return the version in which this API status began
     */
    String since() default "";
}
