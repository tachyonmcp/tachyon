/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.api.server.config.JsonConfig;
import dev.tachyonmcp.api.server.config.RuntimeConfig;
import dev.tachyonmcp.api.server.config.ServerIdentity;
import dev.tachyonmcp.api.server.extensions.ServerExtension;
import dev.tachyonmcp.api.server.features.completions.Completions;
import dev.tachyonmcp.api.server.features.prompts.Prompts;
import dev.tachyonmcp.api.server.features.resources.Resources;
import dev.tachyonmcp.api.server.features.tools.Tools;
import dev.tachyonmcp.core.server.config.CapabilitiesConfig;
import dev.tachyonmcp.core.server.config.NetworkConfig;
import dev.tachyonmcp.core.server.config.ObservabilityConfig;
import dev.tachyonmcp.core.server.config.SecurityConfig;
import dev.tachyonmcp.core.server.config.ServerConfig;
import dev.tachyonmcp.core.server.config.SessionConfig;
import io.netty.channel.ChannelPipeline;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/** Configures and builds a {@link TachyonServer}; call {@link TachyonServer#start()} to bind its transport. */
public interface ServerBuilder {

    /** Configures server identity. */
    ServerBuilder info(Consumer<ServerIdentity.Builder> configurer);

    /** Configures advertised MCP capabilities. */
    ServerBuilder capabilities(Consumer<CapabilitiesConfig.Builder> configurer);

    /**
     * Configures session lifecycle and persistence. Configuring any session option enables sessions;
     * an untouched session configuration leaves the server stateless.
     */
    ServerBuilder session(Consumer<SessionConfig.Builder> configurer);

    /**
     * Declares the server stateless: no session is created and no session state is retained. This is
     * the default; call it to write the choice down.
     *
     * <p>Combining it with a session option is a contradiction and fails at {@link #buildConfig()}
     * with {@link SessionConfig#SESSION_OPTIONS_REQUIRE_ENABLED}.
     *
     * @return this builder
     */
    @SuppressWarnings("removal")
    default ServerBuilder stateless() {
        return session(session -> session.enabled(false));
    }

    /** Configures the network transport. */
    ServerBuilder network(Consumer<NetworkConfig.Builder> configurer);

    /** Configures handler execution. */
    ServerBuilder runtime(Consumer<RuntimeConfig.Builder> configurer);

    /** Configures the passive MCP observation lifecycle (listeners, payload capture). */
    @ExperimentalApi
    ServerBuilder observability(Consumer<ObservabilityConfig.Builder> configurer);

    /**
     * Configures request authentication. Once an authentication provider is set, every HTTP request
     * is authenticated on its own and handlers read the caller from {@link
     * dev.tachyonmcp.api.runtime.InteractionContext#securityContext()}.
     */
    @ExperimentalApi
    ServerBuilder security(Consumer<SecurityConfig.Builder> configurer);

    /** Configures JSON serialization and validation. */
    ServerBuilder json(Consumer<JsonConfig.Builder> configurer);

    /** Sets the server name. */
    ServerBuilder name(String name);

    /** Sets the listen port. */
    ServerBuilder port(int port);

    /** Sets the server version. */
    ServerBuilder version(String version);

    /** Sets the bind host. */
    ServerBuilder host(String host);

    /**
     * Registers tools through the server's tool façade at the end of {@link #build()}.
     */
    ServerBuilder withTools(Consumer<Tools> registrar);

    /** Registers resources through the server's resource façade at the end of {@link #build()}. */
    ServerBuilder withResources(Consumer<Resources> registrar);

    /** Registers prompts through the server's prompt façade at the end of {@link #build()}. */
    ServerBuilder withPrompts(Consumer<Prompts> registrar);

    /** Registers completions through the server's completion façade at the end of {@link #build()}. */
    ServerBuilder withCompletions(Consumer<Completions> registrar);

    /**
     * Registers one or more {@link ServerExtension}.
     */
    ServerBuilder withExtensions(ServerExtension... extensions);

    /** Sets the thread factory used by the server-owned virtual-thread-per-task executor. */
    ServerBuilder threadFactory(ThreadFactory threadFactory);

    /**
     * Configures annotation-based feature registration. The configurer receives an {@link
     * AnnotationContext} through which {@link dev.tachyonmcp.api.server.features.annotations.AnnotationProvider}s
     * and application objects are registered.
     *
     * <p>Registrations are executed after the server is constructed but before {@link #build()}
     * returns. Annotated features are registered through the same Tachyon feature façades as
     * manual registrations.
     *
     * <p>Calling this method more than once composes: every configurer runs against the same
     * {@link AnnotationContext}, in call order.
     *
     * @param configurer the annotation context configurer
     * @return this builder
     */
    @ExperimentalApi
    ServerBuilder annotations(Consumer<AnnotationContext> configurer);

    /**
     * Customizes each Netty channel pipeline. Runs after every built-in handler is added. The
     * {@code mcp-endpoint} handler is the pipeline's only path check: removing or replacing it
     * serves MCP on every path.
     *
     * <p>Removing or replacing Tachyon-provided transport or security handlers may disable CORS,
     * DNS-rebinding protection, protocol validation, or other transport guarantees. Custom pipeline
     * configurations are responsible for preserving equivalent protections. Removing the {@code cors}
     * handler opts out of Tachyon's CORS handling entirely: no response gets CORS headers.
     */
    @ExperimentalApi
    ServerBuilder pipelineCustomizer(@Nullable Consumer<ChannelPipeline> customizer);

    /**
     * Constructs the configured server without starting its transport, then executes the configured
     * feature-registration callbacks.
     */
    TachyonServer build();

    /** Builds the immutable server configuration. */
    ServerConfig buildConfig();
}
