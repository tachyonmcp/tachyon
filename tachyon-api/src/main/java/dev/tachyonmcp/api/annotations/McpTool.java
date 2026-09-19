/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Exposes a plain Java method as an MCP tool. Nothing else is required: the tool name defaults to
 * the method name, and the input and output schemas are derived from the method signature.
 *
 * <pre>{@code
 * class WeatherService {
 *     record Forecast(String city, double celsius) {}
 *
 *     @McpTool
 *     Forecast forecast(String city) {
 *         return new Forecast(city, 22.5);
 *     }
 * }
 *
 * TachyonServer.builder()
 *     .annotations(a -> a.register(new WeatherService()))
 *     .build();
 * }</pre>
 *
 * <p><b>Input binding.</b> Parameters of type {@code InteractionContext} and parameters annotated
 * with {@link Meta} are injected and never advertised. When exactly one argument remains and it
 * is a record, POJO, or {@code Map}, the whole {@code arguments} object is decoded into it, unless
 * {@link McpParam} explicitly selects named binding. Otherwise each argument binds by its
 * {@link McpParam#name()} or Java parameter name (the latter requires {@code -parameters}).
 * {@link McpParam#description()} describes the schema property. Named arguments are required
 * unless {@code Optional}-typed or JSpecify {@code @Nullable}.
 *
 * <p><b>Result mapping.</b> {@code void}/{@code null} returns empty content; {@code ToolResult}
 * passes through; {@code String}, numbers, and booleans become text; a {@code ContentBlock}
 * becomes content; collections become JSON text; any other object becomes structured content and
 * its type is advertised as the tool's {@code outputSchema}.
 *
 * <p>Checked exceptions propagate to the dispatcher exactly as from a {@code ToolFn}.
 */
@Documented
@ExperimentalApi
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface McpTool {

    /**
     * Tool name, unique within the server.
     *
     * @return the tool name, or empty to use the method name
     */
    String name() default "";

    /**
     * Human- and model-readable description.
     *
     * @return the description, or empty for none
     */
    String description() default "";
}
