/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates that an annotated element is legacy (not deprecated) and may be subject to
 * removal in future versions. This annotation serves as a warning
 * to developers that the API might be moved or removed in the future.
 * <p>
 * Example is MCP Tasks: tasks/list and awaitResult are deprecated in 2026-07-28 MCP protocol.
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
public @interface LegacyApi {

    /**
     * Version in which the annotated API became legacy.
     *
     * @return the version in which this API status began
     */
    String since() default "";
}
