/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server;

import dev.tachyonmcp.api.json.JsonSchemaValidator;
import dev.tachyonmcp.api.json.PayloadDeserializer;
import dev.tachyonmcp.api.json.PayloadSerializer;
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
import dev.tachyonmcp.core.server.config.ServerConfig;
import dev.tachyonmcp.core.server.config.SessionConfig;
import dev.tachyonmcp.core.server.features.tasks.TasksExtension;
import dev.tachyonmcp.core.server.json.JacksonPayloadSerde;
import dev.tachyonmcp.core.server.json.NetworkntJsonSchemaValidator;
import dev.tachyonmcp.core.server.session.InMemorySessionEventStore;
import dev.tachyonmcp.core.server.session.InMemorySessionStore;
import io.netty.channel.ChannelPipeline;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Fluent builder for {@link TachyonServer} configuration.
 */
final class DefaultServerBuilder implements ServerBuilder {

    private final ServerIdentity.Builder identityBuilder = ServerIdentity.builder();
    private final CapabilitiesConfig.Builder capabilitiesConfig = CapabilitiesConfig.builder();
    private final SessionConfig.Builder sessionBuilder = SessionConfig.builder();
    private final NetworkConfig.Builder networkBuilder = NetworkConfig.builder();
    private final RuntimeConfig.Builder runtimeBuilder = RuntimeConfig.builder();
    private final ObservabilityConfig.Builder observabilityBuilder = ObservabilityConfig.builder();
    private final List<ServerExtension> extensions = new ArrayList<>();
    private final Set<String> extensionIds = new HashSet<>();
    private final List<Consumer<TachyonServer>> bootstrapRegistrations = new ArrayList<>();

    private JsonSchemaValidator inputSchemaValidator = new NetworkntJsonSchemaValidator();
    private JsonSchemaValidator outputSchemaValidator = new NetworkntJsonSchemaValidator();
    private PayloadSerializer payloadSerializer = new JacksonPayloadSerde();
    private PayloadDeserializer payloadDeserializer = new JacksonPayloadSerde();

    @Nullable
    private Consumer<ChannelPipeline> pipelineCustomizer;

    private @Nullable ThreadFactory threadFactory;

    DefaultServerBuilder() {}

    // === Configuration groups ===

    /**
     * Configures server identity (name, version, etc.).
     */
    @Override
    public ServerBuilder info(Consumer<ServerIdentity.Builder> configurer) {
        configurer.accept(identityBuilder);
        return this;
    }

    /**
     * Configures which MCP capabilities are enabled.
     */
    @Override
    public ServerBuilder capabilities(Consumer<CapabilitiesConfig.Builder> configurer) {
        configurer.accept(capabilitiesConfig);
        return this;
    }

    /**
     * Configures session lifecycle settings.
     */
    @Override
    public ServerBuilder session(Consumer<SessionConfig.Builder> configurer) {
        configurer.accept(sessionBuilder);
        return this;
    }

    /**
     * Configures network settings (host, port, CORS, etc.).
     */
    @Override
    public ServerBuilder network(Consumer<NetworkConfig.Builder> configurer) {
        configurer.accept(networkBuilder);
        return this;
    }

    /**
     * Configures handler-execution runtime settings (shutdown grace period, etc.).
     */
    @Override
    public ServerBuilder runtime(Consumer<RuntimeConfig.Builder> configurer) {
        configurer.accept(runtimeBuilder);
        return this;
    }

    /**
     * Configures the passive MCP observation lifecycle (listeners, payload capture).
     */
    @Override
    public ServerBuilder observability(Consumer<ObservabilityConfig.Builder> configurer) {
        configurer.accept(observabilityBuilder);
        return this;
    }

    /**
     * Configures JSON payload settings (serde, input/output schema validators).
     */
    @Override
    public ServerBuilder json(Consumer<JsonConfig.Builder> configurer) {
        var builder = JsonConfig.builder();
        configurer.accept(builder);
        var config = builder.build();
        if (config.serializer() != null) {
            payloadSerializer = config.serializer();
        }
        if (config.deserializer() != null) {
            payloadDeserializer = config.deserializer();
        }
        if (config.inputValidator() != null) {
            inputSchemaValidator = config.inputValidator();
        }
        if (config.outputValidator() != null) {
            outputSchemaValidator = config.outputValidator();
        }
        return this;
    }

    /**
     * Sets the server name (shorthand for {@code info(b -> b.name(name))}).
     */
    @Override
    public ServerBuilder name(String name) {
        identityBuilder.name(name);
        return this;
    }

    /**
     * Sets the listen port (shorthand for {@code network(b -> b.port(port))}).
     */
    @Override
    public ServerBuilder port(int port) {
        networkBuilder.port(port);
        return this;
    }

    /**
     * Sets the server version (shorthand for {@code info(b -> b.version(version))}).
     */
    @Override
    public ServerBuilder version(String version) {
        identityBuilder.version(version);
        return this;
    }

    /**
     * Sets the bind address (shorthand for {@code network(b -> b.host(host))}).
     */
    @Override
    public ServerBuilder host(String host) {
        networkBuilder.host(host);
        return this;
    }

    /**
     * Registers tools through the server's tool façade after construction.
     *
     * @param registrar tool registrations to apply before this builder returns the server
     * @return this builder
     */
    @Override
    public ServerBuilder withTools(Consumer<Tools> registrar) {
        bootstrapRegistrations.add(server -> registrar.accept(server.tools()));
        return this;
    }

    /**
     * Registers resources through the server's resource façade after construction.
     *
     * @param registrar resource registrations to apply before this builder returns the server
     * @return this builder
     */
    @Override
    public ServerBuilder withResources(Consumer<Resources> registrar) {
        bootstrapRegistrations.add(server -> registrar.accept(server.resources()));
        return this;
    }

    /**
     * Registers prompts through the server's prompt façade after construction.
     *
     * @param registrar prompt registrations to apply before this builder returns the server
     * @return this builder
     */
    @Override
    public ServerBuilder withPrompts(Consumer<Prompts> registrar) {
        bootstrapRegistrations.add(server -> registrar.accept(server.prompts()));
        return this;
    }

    /**
     * Registers completions through the server's completion façade after construction.
     *
     * @param registrar completion registrations to apply before this builder returns the server
     * @return this builder
     */
    @Override
    public ServerBuilder withCompletions(Consumer<Completions> registrar) {
        bootstrapRegistrations.add(server -> registrar.accept(server.completions()));
        return this;
    }

    @Override
    public ServerBuilder withExtensions(ServerExtension... extensions) {
        for (var extension : extensions) {
            addExtension(extension);
        }
        return this;
    }

    private void addExtension(ServerExtension extension) {
        if (!extensionIds.add(extension.extensionId())) {
            throw new IllegalArgumentException("Duplicate extension ID: " + extension.extensionId());
        }

        extensions.add(extension);
    }

    /**
     * Sets a thread factory for virtual-thread-per-task executor creation. The server owns this
     * executor and will shut it down on close.
     */
    @Override
    public ServerBuilder threadFactory(ThreadFactory threadFactory) {
        this.threadFactory = threadFactory;
        return this;
    }

    // === Annotation-based registration ===

    private final List<Consumer<AnnotationContext>> annotationConfigurers = new ArrayList<>();

    @Override
    public ServerBuilder annotations(Consumer<AnnotationContext> configurer) {
        annotationConfigurers.add(configurer);
        return this;
    }

    private void applyAnnotationRegistrations(TachyonServer server) {
        if (annotationConfigurers.isEmpty()) return;
        server.annotations(ctx -> annotationConfigurers.forEach(configurer -> configurer.accept(ctx)));
    }

    // === Transport escape hatch ===

    /**
     * Provides a customizer for the Netty channel pipeline.
     */
    @Override
    public ServerBuilder pipelineCustomizer(@Nullable Consumer<ChannelPipeline> customizer) {
        this.pipelineCustomizer = customizer;
        return this;
    }

    // === Terminal methods ===

    /**
     * Builds a configured {@link TachyonServer} without binding a transport.
     *
     * <p>The returned server includes the configured sessions, features, extensions, payload
     * processing, and execution strategy. Transport-dependent host and port values become meaningful
     * only after {@link TachyonServer#start()}.
     *
     * @return the configured server
     */
    @Override
    public TachyonServer build() {
        var sessionConfig = sessionBuilder.build();
        var sessionEventStore = sessionConfig.sessionEventStore() != null
                ? sessionConfig.sessionEventStore()
                : new InMemorySessionEventStore();
        var store = sessionConfig.sessionStore() != null ? sessionConfig.sessionStore() : new InMemorySessionStore();
        var serverConfig = buildConfig();
        if (serverConfig.capabilities().tasks().enabled() && !extensionIds.contains(TasksExtension.ID)) {
            addExtension(TasksExtension.instance());
        }
        var allExtensions = List.copyOf(extensions);
        final ExecutorService resolvedExecutor;
        if (threadFactory != null) {
            resolvedExecutor = Executors.newThreadPerTaskExecutor(threadFactory);
        } else {
            resolvedExecutor = DefaultTachyonServer.defaultExecutorForBuilder();
        }
        var server = new DefaultTachyonServer(
                resolvedExecutor,
                sessionEventStore,
                store,
                serverConfig,
                inputSchemaValidator,
                outputSchemaValidator,
                payloadSerializer,
                payloadDeserializer,
                null,
                allExtensions,
                pipelineCustomizer);
        try {
            bootstrapRegistrations.forEach(registrar -> registrar.accept(server));
            applyAnnotationRegistrations(server);
            server.validateConfiguration();
        } catch (Throwable t) {
            server.close();
            throw t;
        }
        return server;
    }

    /**
     * Builds the {@link ServerConfig} from the current builder state.
     */
    @Override
    public ServerConfig buildConfig() {
        return ServerConfig.builder()
                .identity(identityBuilder.build())
                .capabilities(capabilitiesConfig.build())
                .session(sessionBuilder.build())
                .network(networkBuilder.build())
                .runtime(runtimeBuilder.build())
                .observability(observabilityBuilder.build())
                .build();
    }
}
