/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.api.server.features.annotations.AnnotationProvider;
import dev.tachyonmcp.core.server.annotations.TachyonAnnotationProvider;
import java.util.ArrayList;
import java.util.List;

/**
 * DSL entry point for registering annotated application objects through {@link
 * AnnotationProvider}s. Passed to the {@link ServerBuilder#annotations} configurer.
 *
 * <p>The active provider starts as {@link TachyonAnnotationProvider}, which reads Tachyon's own
 * {@link dev.tachyonmcp.api.annotations.McpTool @McpTool}, {@link
 * dev.tachyonmcp.api.annotations.McpResource @McpResource}, and {@link
 * dev.tachyonmcp.api.annotations.McpPrompt @McpPrompt}. Each call to {@link #withProvider} switches
 * the active provider for subsequent {@link #register} calls. Multiple providers and multiple
 * objects are supported.
 *
 * <p>Example:
 *
 * <pre>{@code
 * TachyonServer.builder()
 *     .annotations(a -> a
 *         .register(new WeatherService())
 *         .withProvider(new McpJavaAnnotationProvider())
 *         .register(new CalculatorService()))
 *     .build();
 * }</pre>
 */
@ExperimentalApi
public final class AnnotationContext {

    private final List<Registration> registrations = new ArrayList<>();

    private AnnotationProvider currentProvider = TachyonAnnotationProvider.instance();

    /**
     * Sets the active annotation provider for subsequent {@link #register} calls.
     *
     * @param provider the provider
     * @return this context for chaining
     */
    public AnnotationContext withProvider(AnnotationProvider provider) {
        if (provider == null) throw new IllegalArgumentException("provider must not be null");
        currentProvider = provider;
        return this;
    }

    /**
     * Registers {@code instance} using the active provider. The provider inspects the object for
     * annotated methods and registers the resulting features through the server's feature façades.
     *
     * @param instance the application object to scan
     * @return this context for chaining
     */
    public AnnotationContext register(Object instance) {
        if (instance == null) throw new IllegalArgumentException("instance must not be null");
        registrations.add(new Registration(currentProvider, instance));
        return this;
    }

    /** Returns all accumulated registrations for deferred execution. */
    List<Registration> registrations() {
        return List.copyOf(registrations);
    }

    /** A pending provider + instance pair. */
    record Registration(AnnotationProvider provider, Object instance) {}
}
