/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a named argument of an {@link McpTool}, {@link McpResource}, {@link McpPrompt} or
 * {@link McpCompletion} method.
 *
 * <p>Without this annotation arguments bind by Java parameter name, which requires compiling with
 * {@code -parameters}. An explicit nonblank {@link #name()} removes that dependency;
 * {@link #description()} adds a schema description.
 *
 * <p>Presence of {@code @McpParam} on any parameter of a tool method forces named binding: a
 * single object parameter is then treated as one named argument, not as the whole arguments object.
 */
@Documented
@ExperimentalApi
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface McpParam {

    /** Argument name as advertised to clients; blank means the Java parameter name. */
    String name() default "";

    /** Argument description emitted into the JSON schema / prompt argument; blank means absent. */
    String description() default "";
}
