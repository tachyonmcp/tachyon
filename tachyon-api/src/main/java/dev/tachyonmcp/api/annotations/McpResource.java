/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Exposes a plain Java method as an MCP resource read by {@code resources/read}.
 *
 * <pre>{@code
 * @McpResource(uri = "config://app")
 * AppConfig config() { ... }
 *
 * @McpResource(uri = "users://{id}")
 * String user(String id) { ... }
 * }</pre>
 *
 * <p>A {@link #uri()} containing {@code {...}} registers a resource template; each template
 * variable binds by {@link McpParam#name()} or Java parameter name (the latter requires
 * {@code -parameters}). Parameters of type {@code InteractionContext} and parameters annotated
 * with {@link Meta} are injected. Template variables and non-injected parameter names must match
 * exactly; missing or extra names fail registration. Static resources may also inject metadata.
 *
 * <p><b>Result mapping.</b> {@code ResourceContents} passes through; {@code String} becomes text;
 * {@code byte[]} becomes a blob; any other object becomes JSON text with {@code application/json}
 * as the default MIME type. Returning {@code null} is an error.
 */
@Documented
@ExperimentalApi
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface McpResource {

    /**
     * Resource URI, or an RFC 6570 URI template when it contains {@code {...}}.
     *
     * @return the resource URI or URI template
     */
    String uri();

    /**
     * Resource name.
     *
     * @return the name, or empty to use the method name
     */
    String name() default "";

    /**
     * Human- and model-readable description.
     *
     * @return the description, or empty for none
     */
    String description() default "";

    /**
     * MIME type of the contents.
     *
     * @return the MIME type, or empty to infer it from the return value
     */
    String mimeType() default "";
}
