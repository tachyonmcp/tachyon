/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.annotations;

import dev.tachyonmcp.api.server.domain.Role;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Exposes a plain Java method as an MCP prompt served by {@code prompts/get}.
 *
 * <pre>{@code
 * @McpPrompt
 * String review(String code) {
 *     return "Review this code:\n" + code;
 * }
 * }</pre>
 *
 * <p>Each parameter is one prompt argument named after the parameter (compile with {@code
 * -parameters}); it is required unless it is {@code Optional}-typed or JSpecify {@code @Nullable}.
 * A parameter of type {@code InteractionContext} is injected.
 *
 * <p><b>Result mapping.</b> {@code PromptResult} passes through; a {@code String}, {@code
 * ContentBlock}, or any other object (as JSON text) becomes one message with {@link #role()}; a
 * {@code PromptMessage} keeps its own role; a {@code List} of those becomes the message list.
 */
@Documented
@ExperimentalApi
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface McpPrompt {

    /**
     * Prompt name, unique within the server.
     *
     * @return the prompt name, or empty to use the method name
     */
    String name() default "";

    /**
     * Human- and model-readable description.
     *
     * @return the description, or empty for none
     */
    String description() default "";

    /**
     * Prompt role.
     *
     * @return Prompt role, default value is {@link Role#USER}
     */
    Role role() default Role.USER;
}
