/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Supplies MCP argument completions for exactly one prompt name or resource URI/template.
 *
 * <pre>{@code
 * @McpCompletion(prompt = "trip")
 * List<String> cities(String city, @Nullable String country) {
 *     return searchCities(city, country);
 * }
 * }</pre>
 *
 * <p>In the named signature, the first non-injected parameter must be a {@code String}; its
 * {@link McpParam#name()} or reflection name selects the argument being completed, and its value
 * is the current partial text.
 * Other named scalar parameters bind from previously resolved sibling arguments. Missing siblings
 * are required unless {@code Optional}-typed or JSpecify {@code @Nullable}. Compile with
 * {@code -parameters} unless every named parameter has an explicit {@link McpParam#name()}. Requests for a different argument return no candidates. When the target
 * prompt or resource template is already known, a completed argument it does not declare fails
 * registration.
 *
 * <p>Enum-typed prompt and resource template arguments complete from constant names automatically;
 * declaring {@code @McpCompletion} for the same target in the same service replaces that default.
 *
 * <p>Alternatively, take a single {@code CompletionRequest} to handle all arguments of the target
 * and read the partial value, resolved siblings, and request metadata directly. Either signature
 * may include an {@code InteractionContext} parameter and one {@link Meta} parameter. Do not mix {@code CompletionRequest} with
 * named argument parameters.
 *
 * <p>Return {@code CompletionResult} to preserve total, hasMore, and metadata, or {@code List<String>}
 * for candidates only. Null results and null/non-string candidates are rejected. Checked exceptions
 * propagate as from {@code CompletionFn}; invocation runs on a virtual thread. Declare at most one
 * completion method per target in an annotated service; use {@code CompletionRequest} when one
 * target needs completions for several arguments.
 */
@Documented
@ExperimentalApi
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface McpCompletion {
    /**
     * Prompt name whose arguments this method completes.
     *
     * @return the prompt name, or empty when {@link #resource()} is specified
     */
    String prompt() default "";

    /**
     * Resource URI or URI template, matched verbatim against the completion reference.
     *
     * @return the URI/template, or empty when {@link #prompt()} is specified
     */
    String resource() default "";
}
