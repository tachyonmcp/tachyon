/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.api.runtime.Notifications;
import dev.tachyonmcp.api.server.extensions.ServerExtension;
import dev.tachyonmcp.api.server.features.completions.Completions;
import dev.tachyonmcp.api.server.features.prompts.Prompts;
import dev.tachyonmcp.api.server.features.resources.Resources;
import dev.tachyonmcp.api.server.features.tools.Tools;
import dev.tachyonmcp.core.server.config.ServerConfig;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The one public type users hold — an MCP server that is {@link AutoCloseable}.
 *
 * <p>Created via {@link #builder()} (Java) or {@code TachyonServer(port) { }} (Kotlin).
 * Call {@link #close()} to shut down the transport and release all resources.
 *
 * <p>Kotlin users get {@code .use { }} from the stdlib via {@code AutoCloseable}.
 */
public interface TachyonServer extends AutoCloseable {

    /**
     * Returns the tool registry.
     */
    Tools tools();

    /**
     * Returns the resource registry.
     */
    Resources resources();

    /**
     * Returns the prompt registry.
     */
    Prompts prompts();

    /**
     * Returns the completion registry.
     */
    Completions completions();

    /**
     * Registers annotated objects using this server's registries and configured payload codecs.
     * DI containers can call this after constructing their beans and before {@link #start()}.
     * Registrations are applied immediately, in order, and are not atomic as a group.
     *
     * @param configurer annotation providers and objects to register
     */
    @ExperimentalApi
    void annotations(Consumer<AnnotationContext> configurer);

    Notifications notifications();

    /**
     * Starts the configured transport. Feature registries may be populated before this call.
     * Starts again after {@link #stop()}, binding a new transport (port {@code 0} picks a new port).
     *
     * @throws IllegalStateException if the server is already started or {@linkplain #close() closed}
     */
    void start();

    /**
     * Stops the transport and keeps the server, so {@link #start()} can serve it again.
     *
     * <p>Stops accepting connections, ends open {@code subscriptions/listen} streams gracefully,
     * waits up to {@code shutdownGracePeriod} for in-flight requests, then closes every connection:
     * a response not written by then is dropped, so a slow client or handler cannot delay the stop.
     * Registries, extensions, the handler executor and sessions stay: a client resumes its session on
     * the restarted transport. No-op when not started or already closed.
     *
     * <p>Blocks while draining, so never call it on a transport event loop.
     *
     * @throws IllegalStateException if called on a transport event loop thread
     */
    @ExperimentalApi
    void stop();

    /**
     * Returns the port the server is bound to.
     *
     * @throws IllegalStateException if the server has not been started
     */
    int port();

    /**
     * Returns the host the server is bound to, or the configured host if not yet started.
     * Only meaningful after {@link #start()} or equivalent.
     */
    String host();

    /**
     * Returns the server configuration.
     */
    ServerConfig config();

    /**
     * Returns the registered extensions.
     */
    List<ServerExtension> extensions();

    /**
     * Returns the registered extension of the given type, e.g. to reach the runtime facade a
     * configurable extension exposes.
     *
     * @param type the extension class to look up
     * @param <E> the extension type
     * @return the first registered extension that is an instance of {@code type}, or empty
     */
    default <E extends ServerExtension> Optional<E> extension(Class<E> type) {
        for (var extension : extensions()) {
            if (type.isInstance(extension)) {
                return Optional.of(type.cast(extension));
            }
        }
        return Optional.empty();
    }

    /**
     * Shuts down the server and releases its resources. Terminal: {@link #start()} throws afterwards.
     */
    @Override
    void close();

    static ServerBuilder builder() {
        return new DefaultServerBuilder();
    }
}
