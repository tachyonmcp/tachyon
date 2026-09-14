/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server;

import dev.tachyonmcp.api.json.JsonObject;
import dev.tachyonmcp.api.json.JsonSchemaValidator;
import dev.tachyonmcp.api.json.PayloadDeserializer;
import dev.tachyonmcp.api.json.PayloadSerializer;
import dev.tachyonmcp.api.json.spi.JsonSchemaFactory;
import dev.tachyonmcp.api.runtime.Notifications;
import dev.tachyonmcp.api.server.config.RuntimeConfig;
import dev.tachyonmcp.api.server.domain.LoggingLevel;
import dev.tachyonmcp.api.server.domain.ProgressToken;
import dev.tachyonmcp.api.server.domain.RequestId;
import dev.tachyonmcp.api.server.domain.ServerCapabilities;
import dev.tachyonmcp.api.server.extensions.ExtensionContext;
import dev.tachyonmcp.api.server.extensions.ExtensionMethodHandler;
import dev.tachyonmcp.api.server.extensions.ExtensionNegotiation;
import dev.tachyonmcp.api.server.extensions.ServerExtension;
import dev.tachyonmcp.api.server.features.completions.Completions;
import dev.tachyonmcp.api.server.features.prompts.Prompts;
import dev.tachyonmcp.api.server.features.resources.Resources;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.api.server.features.tasks.TaskSupport;
import dev.tachyonmcp.api.server.features.tasks.Tasks;
import dev.tachyonmcp.api.server.features.tools.Tools;
import dev.tachyonmcp.api.server.session.SessionIdGenerator;
import dev.tachyonmcp.core.protocol.Protocol;
import dev.tachyonmcp.core.protocol.ProtocolResponseMapper;
import dev.tachyonmcp.core.protocol.Protocols;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.McpProtocol;
import dev.tachyonmcp.core.runtime.Backpressure;
import dev.tachyonmcp.core.runtime.Session;
import dev.tachyonmcp.core.runtime.SessionState;
import dev.tachyonmcp.core.runtime.SseEvent;
import dev.tachyonmcp.core.server.config.ServerConfig;
import dev.tachyonmcp.core.server.config.SessionConfig;
import dev.tachyonmcp.core.server.features.completions.CompletionMethodHandlers;
import dev.tachyonmcp.core.server.features.completions.DefaultCompletionRegistry;
import dev.tachyonmcp.core.server.features.prompts.DefaultPromptRegistry;
import dev.tachyonmcp.core.server.features.prompts.PromptMethodHandlers;
import dev.tachyonmcp.core.server.features.resources.DefaultResourceRegistry;
import dev.tachyonmcp.core.server.features.resources.ResourceMethodHandlers;
import dev.tachyonmcp.core.server.features.subscriptions.SubscriptionRegistry;
import dev.tachyonmcp.core.server.features.tasks.DefaultTaskRegistry;
import dev.tachyonmcp.core.server.features.tasks.TaskMethodHandlers;
import dev.tachyonmcp.core.server.features.tasks.TaskRegistry;
import dev.tachyonmcp.core.server.features.tools.DefaultToolRegistry;
import dev.tachyonmcp.core.server.features.tools.ToolMethodHandlers;
import dev.tachyonmcp.core.server.handlers.DiscoverHandler;
import dev.tachyonmcp.core.server.handlers.InitializeHandler;
import dev.tachyonmcp.core.server.handlers.LoggingHandlers;
import dev.tachyonmcp.core.server.handlers.PingHandler;
import dev.tachyonmcp.core.server.handlers.SubscriptionsListenHandler;
import dev.tachyonmcp.core.server.internal.NotificationLogSupport;
import dev.tachyonmcp.core.server.internal.OperationTracker;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.server.json.JacksonObjectJsonFactory;
import dev.tachyonmcp.core.server.json.JacksonPayloadSerde;
import dev.tachyonmcp.core.server.json.JsonUtils;
import dev.tachyonmcp.core.server.json.NetworkntJsonSchemaValidator;
import dev.tachyonmcp.core.server.session.DispatchContext;
import dev.tachyonmcp.core.server.session.SessionEvent;
import dev.tachyonmcp.core.server.session.SessionEventStore;
import dev.tachyonmcp.core.server.session.SessionManager;
import dev.tachyonmcp.core.server.session.SessionStore;
import dev.tachyonmcp.core.transport.jsonrpc.JsonRpcCodec;
import dev.tachyonmcp.core.transport.netty.NettyServer;
import dev.tachyonmcp.core.transport.netty.NettyServerConfig;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpRequest;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.node.ObjectNode;

final class DefaultTachyonServer implements ServerEngine, ExtensionContext {

    private static final Logger logger = LoggerFactory.getLogger(DefaultTachyonServer.class);

    private record PendingRequestEntry(
            @Nullable String sessionId, @Nullable String channelId, CompletableFuture<String> future) {}

    private final ServerConfig config;
    private final SessionEventStore sessionEventStore;
    private final SessionManager sessionManager;
    private final OutboundStreamResolver outboundStreamResolver = new OutboundSseStreamMessageRouter();
    private final AtomicLong eventIdCounter = new AtomicLong(0);
    private final DefaultToolRegistry toolRegistry;
    private final DefaultResourceRegistry resourceRegistry;
    private final DefaultTaskRegistry taskRegistry;
    private final DefaultPromptRegistry promptRegistry;
    private final DefaultCompletionRegistry completionRegistry;
    private final SubscriptionRegistry subscriptionRegistry;
    private final JsonSchemaValidator inputValidator;
    private final JsonSchemaValidator outputValidator;
    private final PayloadSerializer payloadSerializer;
    private final PayloadDeserializer payloadDeserializer;
    private final Map<String, RpcMethodHandler<?, ?>> methodHandlers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<RequestId, PendingRequestEntry> pendingRequests = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private final OperationTracker operations = new OperationTracker();
    private final List<ServerExtension> extensions;
    private final @Nullable Consumer<ChannelPipeline> pipelineCustomizer;
    private final Map<String, String> extensionMethodOwners = new ConcurrentHashMap<>();
    private final Map<String, ServerExtension> extensionsById = new ConcurrentHashMap<>();
    private final Set<String> optionalNegotiationExtensionIds = ConcurrentHashMap.newKeySet();
    private @Nullable String bootstrappingExtensionId;

    /**
     * Guards {@link #start()} against {@link #close()} and against itself.
     *
     * <p>A {@link ReentrantLock}, not {@code synchronized}: {@code start()} blocks on the Netty
     * bind and {@code close()} blocks draining in-flight operations, so on a virtual thread a
     * monitor would pin the carrier for the whole lifecycle transition.
     */
    private final ReentrantLock lifecycleLock = new ReentrantLock();

    private boolean closed;

    private volatile int port;

    @Nullable
    private volatile String host;

    @Nullable
    private volatile Closeable transport;

    private static final List<ProtocolResponseMapper> RESPONSE_MAPPERS;

    static {
        var mappers = new ArrayList<ProtocolResponseMapper>();
        Protocols.list().stream().map(Protocol::responseMapper).forEach(mappers::add);
        RESPONSE_MAPPERS = List.copyOf(mappers);
    }

    @Override
    public ProtocolResponseMapper responseMapper() {
        for (var mapper : RESPONSE_MAPPERS) {
            if (mapper.supports("mcp", McpProtocol.VERSION)) {
                return mapper;
            }
        }
        throw new IllegalStateException("No protocol response mapper found");
    }

    @Override
    public ServerConfig config() {
        return config;
    }

    @Override
    public boolean isStateless() {
        return !config.session().enabled();
    }

    @Override
    public SessionIdGenerator<? super HttpRequest> sessionIdGenerator() {
        return config.session().sessionIdGenerator();
    }

    @Override
    public ExecutorService executor() {
        return executor;
    }

    @Override
    public OperationTracker operations() {
        return operations;
    }

    @Override
    public RuntimeConfig runtime() {
        return config.runtime();
    }

    @Override
    public void setLoggingLevel(String sessionId, LoggingLevel level) {
        sessionManager.getSession(sessionId).ifPresent(session -> session.loggingLevel(level));
    }

    @Override
    @Nullable
    public LoggingLevel getLoggingLevel(String sessionId) {
        return sessionManager.getSession(sessionId).map(Session::loggingLevel).orElse(null);
    }

    /**
     * Resolves the server capabilities advertised by the configured features and registered handlers.
     *
     * @return the resolved server capabilities
     */
    @Override
    public ServerCapabilities resolveCapabilities() {
        final var builder = ServerCapabilities.builder();

        final var capabilitiesConfig = config.capabilities();

        builder.logging(capabilitiesConfig.logging());
        switch (capabilitiesConfig.completions()) {
            case ON -> builder.completions(true);
            case OFF -> builder.completions(false);
            case AUTO -> builder.completions(!completionRegistry.isEmpty());
        }

        var hasTaskAugmentedTools = toolRegistry.getAll().stream()
                .anyMatch(h ->
                        h.descriptor().taskSupport() != null && h.descriptor().taskSupport() != TaskSupport.FORBIDDEN);
        var tasksConfig = capabilitiesConfig.tasks();
        if (tasksConfig.enabled() || hasTaskAugmentedTools) {
            builder.tasks(new ServerCapabilities.Tasks(
                    tasksConfig.list(), tasksConfig.cancel(), tasksConfig.requests() || hasTaskAugmentedTools));
        }

        var toolsConfig = capabilitiesConfig.tools();
        switch (toolsConfig.mode()) {
            case ON -> builder.tools(new ServerCapabilities.Tools(toolsConfig.listChanged()));
            case OFF -> {}
            case AUTO -> {
                if (!toolRegistry.isEmpty()) {
                    builder.tools(new ServerCapabilities.Tools(toolsConfig.listChanged()));
                }
            }
        }

        var resourcesConfig = capabilitiesConfig.resources();
        switch (resourcesConfig.mode()) {
            case ON ->
                builder.resources(
                        new ServerCapabilities.Resources(resourcesConfig.subscribe(), resourcesConfig.listChanged()));
            case OFF -> {}
            case AUTO -> {
                if (!resourceRegistry.isEmpty()) {
                    builder.resources(new ServerCapabilities.Resources(
                            resourcesConfig.subscribe(), resourcesConfig.listChanged()));
                }
            }
        }

        var promptsConfig = capabilitiesConfig.prompts();
        switch (promptsConfig.mode()) {
            case ON -> builder.prompts(new ServerCapabilities.Prompts(promptsConfig.listChanged()));
            case OFF -> {}
            case AUTO -> {
                if (!promptRegistry.isEmpty()) {
                    builder.prompts(new ServerCapabilities.Prompts(promptsConfig.listChanged()));
                }
            }
        }

        return builder.build();
    }

    void validateConfiguration() {
        // Only TaskSupport.REQUIRED is checked eagerly: it always attempts to create a task, on
        // every call, so a missing connector is unambiguously a misconfiguration. OPTIONAL tools
        // may run synchronously and never touch the connector -- under MCP 2026-07-28 OPTIONAL
        // always runs synchronously, and under 2025-11-25 the client decides per call. FORBIDDEN
        // tools never touch it either. ToolMethodHandlers.mapResult still rejects a
        // REQUIRED-without-connector call at runtime; this just fails faster.
        var hasRequiredTaskTool = toolRegistry.getAll().stream()
                .anyMatch(handler -> handler.descriptor().taskSupport() == TaskSupport.REQUIRED);
        var connectorMissing = !config.capabilities().tasks().enabled() || !taskRegistry.executionConfigured();
        if (hasRequiredTaskTool && connectorMissing) {
            throw new IllegalStateException("Task-producing tools require a TaskConnector");
        }
        if (connectorMissing) {
            var optionalTaskTools = toolRegistry.getAll().stream()
                    .filter(handler -> handler.descriptor().taskSupport() == TaskSupport.OPTIONAL)
                    .map(handler -> handler.descriptor().name())
                    .toList();
            if (!optionalTaskTools.isEmpty()) {
                logger.warn(
                        "Tool(s) {} declare TaskSupport.OPTIONAL but no TaskConnector is configured -- a"
                                + " task-augmented call to them will fail at runtime under MCP 2025-11-25",
                        optionalTaskTools);
            }
        }
    }

    DefaultTachyonServer(
            ExecutorService executor,
            SessionEventStore sessionEventStore,
            SessionStore sessionStore,
            ServerConfig config,
            @Nullable JsonSchemaValidator inputValidator,
            @Nullable JsonSchemaValidator outputValidator,
            @Nullable PayloadSerializer payloadSerializer,
            @Nullable PayloadDeserializer payloadDeserializer,
            @Nullable JsonSchemaFactory<?> schemaFactory,
            @Nullable List<ServerExtension> extensions,
            @Nullable Consumer<ChannelPipeline> pipelineCustomizer) {
        this.executor = executor;
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.sessionEventStore = Objects.requireNonNull(sessionEventStore, "sessionEventStore cannot be null");
        this.port = config.network().port();
        this.extensions = extensions != null ? extensions : List.of();
        this.pipelineCustomizer = pipelineCustomizer;
        var configuredSessionTtl = config.session().sessionTtl();
        this.sessionManager = new SessionManager(
                sessionStore,
                config.runtime().clock(),
                configuredSessionTtl != null ? configuredSessionTtl : SessionConfig.DEFAULT_SESSION_TTL,
                executor);
        final JsonSchemaValidator inputValidator1 =
                inputValidator != null ? inputValidator : new NetworkntJsonSchemaValidator();
        final JsonSchemaValidator outputValidator1 = outputValidator != null ? outputValidator : inputValidator1;
        var defaultSerde = new JacksonPayloadSerde();
        final PayloadSerializer payloadSerializer1 = payloadSerializer != null ? payloadSerializer : defaultSerde;
        final PayloadDeserializer payloadDeserializer1 =
                payloadDeserializer != null ? payloadDeserializer : defaultSerde;
        final JsonSchemaFactory<?> schemaFactory1 = schemaFactory != null ? schemaFactory : discoverSchemaFactory();
        this.inputValidator = inputValidator1;
        this.outputValidator = outputValidator1;
        this.payloadSerializer = payloadSerializer1;
        this.payloadDeserializer = payloadDeserializer1;
        var caps = config.capabilities();
        this.toolRegistry = new DefaultToolRegistry(schemaFactory1, caps.tools());
        this.resourceRegistry = new DefaultResourceRegistry(this, caps.resources());
        this.taskRegistry =
                new DefaultTaskRegistry(this, caps.tasks(), config.runtime().clock());
        this.promptRegistry = new DefaultPromptRegistry(caps.prompts());
        this.completionRegistry = new DefaultCompletionRegistry(caps.completions());
        this.subscriptionRegistry = new SubscriptionRegistry(this);
        registerDefaults();
        bootstrapExtensions();
        setupChangeListeners(config);
        if (config.session().enabled()) {
            var session = config.session();
            assert session.sessionTtl() != null : "compact ctor fills ttl when enabled";
            assert session.janitorInterval() != null : "compact ctor fills janitor when enabled";
            sessionManager.startJanitor(session.sessionTtl(), session.janitorInterval());
        }
    }

    static ExecutorService defaultExecutorForBuilder() {
        return defaultExecutor();
    }

    private static ExecutorService defaultExecutor() {
        return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("tachyon-vt-", 0).factory());
    }

    private static JsonSchemaFactory<?> discoverSchemaFactory() {
        JsonSchemaFactory<?> found = null;
        for (JsonSchemaFactory<?> provider : ServiceLoader.load(JsonSchemaFactory.class)) {
            if (provider.sourceType() == String.class) {
                if (found != null) {
                    throw new IllegalStateException(
                            "Duplicate JsonSchemaFactory implementations handling String sources found: "
                                    + found.getClass().getName() + " and "
                                    + provider.getClass().getName());
                }
                found = provider;
            }
        }
        if (found == null) {
            throw new IllegalStateException(
                    "No JsonSchemaFactory implementation handling String sources registered via ServiceLoader.");
        }
        return found;
    }

    @Override
    public void start() {
        lifecycleLock.lock();
        try {
            if (closed) {
                throw new IllegalStateException("Server is closed");
            }
            if (transport != null) {
                throw new IllegalStateException("Server is already started");
            }
            var network = config.network();
            if (network.port() < 0) {
                throw new IllegalStateException("Port must be set before start()");
            }
            var netty = new NettyServer(
                    this,
                    new NettyServerConfig(
                            network.host(),
                            network.port(),
                            network.endpointPath(),
                            network.readerIdleTimeout(),
                            network.writerIdleTimeout(),
                            network.maxContentLength(),
                            NettyServerConfig.buildCorsConfig(
                                    network.allowedOrigins(),
                                    network.allowNullOrigin(),
                                    network.allowPrivateNetworks(),
                                    network.allowedHeaders()),
                            network.allowedHosts(),
                            network.ioEngine(),
                            pipelineCustomizer));
            host = netty.host();
            port = netty.port();
            transport = netty;
        } finally {
            lifecycleLock.unlock();
        }
    }

    @Override
    public int port() {
        if (transport == null) {
            throw new IllegalStateException("Server not started; call start() first");
        }
        return port;
    }

    @Override
    public String host() {
        return host != null ? host : config().network().host();
    }

    private void setupChangeListeners(ServerConfig config) {
        var caps = config.capabilities();
        if (caps.tools().listChanged()) {
            toolRegistry.onChange(() -> {
                broadcastNotification("notifications/tools/list_changed");
                subscriptionRegistry.notifyToolsListChanged();
            });
        }
        if (caps.resources().listChanged()) {
            resourceRegistry.onChange(() -> {
                broadcastNotification("notifications/resources/list_changed");
                subscriptionRegistry.notifyResourcesListChanged();
            });
        }
        if (caps.prompts().listChanged()) {
            promptRegistry.onChange(() -> {
                broadcastNotification("notifications/prompts/list_changed");
                subscriptionRegistry.notifyPromptsListChanged();
            });
        }
        if (caps.tasks().list()) {
            taskRegistry.onChange(() -> broadcastNotification("notifications/tasks/list_changed"));
        }
        taskRegistry.startTtlJanitor();
    }

    void broadcastNotification(String method) {
        broadcastNotification(method, java.util.Map.of());
    }

    @Override
    public void broadcastNotification(String method, Object params) {
        var paramsStr = JsonRpcCodec.toJsonParams(params);
        var notificationJson = JsonRpcCodec.serializeNotificationAsString(method, paramsStr);
        for (var entry : sessionManager.allSessions()) {
            if (entry.state() == SessionState.ACTIVE) {
                sendSerializedNotification(entry, method, paramsStr, notificationJson, null);
            }
        }
    }

    @Override
    public void notifyTaskStatus(TaskSnapshot snapshot, @Nullable String sessionId) {
        if (sessionId != null) {
            getSession(sessionId).ifPresent(session -> notifyTaskStatus(session, snapshot));
        } else {
            for (var session : sessionManager.allSessions()) {
                if (session.state() == SessionState.ACTIVE) {
                    notifyTaskStatus(session, snapshot);
                }
            }
        }
        // Session-based delivery above serves legacy (2025-11-25) requestors. Modern (2026-07-28)
        // requestors opt in per taskId via subscriptions/listen, independent of any session.
        subscriptionRegistry.notifyTaskStatus(snapshot);
    }

    @Override
    public void notifyTaskProgress(
            ProgressToken progressToken,
            @Nullable String sessionId,
            double progress,
            @Nullable Double total,
            @Nullable String message) {
        if (sessionId != null) {
            getSession(sessionId)
                    .ifPresent(session -> notifyTaskProgress(session, progressToken, progress, total, message));
        } else {
            for (var session : sessionManager.allSessions()) {
                if (session.state() == SessionState.ACTIVE) {
                    notifyTaskProgress(session, progressToken, progress, total, message);
                }
            }
        }
    }

    @Override
    public void notifyResourceSubscriptions(String uri) {
        subscriptionRegistry.notifyResourceUpdated(uri);
    }

    private void notifyTaskStatus(Session session, TaskSnapshot snapshot) {
        var protocol = session.protocol();
        var params = (protocol != null ? protocol.responseMapper() : responseMapper())
                .taskStatusNotificationParams(snapshot);
        var paramsJson = JsonUtils.writeString(params);
        var notificationJson = JsonRpcCodec.serializeNotificationAsString("notifications/tasks/status", paramsJson);
        sendSerializedNotification(session, "notifications/tasks/status", paramsJson, notificationJson, null);
    }

    private void notifyTaskProgress(
            Session session,
            ProgressToken progressToken,
            double progress,
            @Nullable Double total,
            @Nullable String message) {
        var protocol = session.protocol();
        var mapper = protocol != null ? protocol.responseMapper() : responseMapper();
        var params = mapper.progressNotificationParams(progressToken, progress, total, message);
        var paramsJson = JsonUtils.writeString(params);
        var notificationJson = JsonRpcCodec.serializeNotificationAsString("notifications/progress", paramsJson);
        sendSerializedNotification(session, "notifications/progress", paramsJson, notificationJson, null);
    }

    private void registerDefaults() {
        methodHandlers.put("initialize", new InitializeHandler(this));
        methodHandlers.put("server/discover", new DiscoverHandler(this));
        methodHandlers.put("ping", new PingHandler());
        methodHandlers.put("subscriptions/listen", new SubscriptionsListenHandler(subscriptionRegistry));
        ToolMethodHandlers.register(
                methodHandlers, toolRegistry, inputValidator, outputValidator, payloadSerializer, payloadDeserializer);
        ResourceMethodHandlers.register(methodHandlers, resourceRegistry);
        TaskMethodHandlers.register(methodHandlers, taskRegistry);
        PromptMethodHandlers.register(methodHandlers, promptRegistry, inputValidator);
        CompletionMethodHandlers.register(methodHandlers, completionRegistry);
        if (config.capabilities().logging()) {
            LoggingHandlers.register(methodHandlers);
        }
    }

    @Override
    public void registerHandler(RpcMethodHandler<?, ?> handler) {
        methodHandlers.put(handler.method(), handler);
        logger.debug("Handler registered: {}", handler.method());
    }

    @Override
    public void registerHandler(String method, RpcMethodHandler<?, ?> handler) {
        methodHandlers.put(method, handler);
        logger.debug("Handler registered: {}", method);
    }

    @Override
    @Nullable
    public RpcMethodHandler<?, ?> getHandler(String method) {
        return methodHandlers.get(method);
    }

    @Override
    public void registerHandler(String method, ExtensionMethodHandler handler) {
        var ownerId = bootstrappingExtensionId;
        if (ownerId != null) {
            extensionMethodOwners.putIfAbsent(method, ownerId);
        }
        registerHandler(method, new RpcMethodHandler<JsonObject, Object>() {
            @Override
            public String method() {
                return method;
            }

            @Override
            public JsonObject decode(DispatchContext context, @Nullable Object rawParams) {
                return toJsonObject(rawParams);
            }

            @Override
            public Object handle(DispatchContext context, JsonObject params) throws Exception {
                var result = handler.handle(context, params);
                return result != null ? result : context.responseMapper().emptyResult();
            }
        });
    }

    /**
     * Wraps raw {@code params} for an extension method. A parsed tree is wrapped in place rather
     * than flattened: {@link dev.tachyonmcp.core.transport.jsonrpc.JsonRpcCodec} already parsed it.
     */
    private static JsonObject toJsonObject(@Nullable Object params) {
        if (params instanceof ObjectNode node
                && JacksonObjectJsonFactory.INSTANCE.toJsonDocument(node) instanceof JsonObject object) {
            return object;
        }
        if (params instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            var typed = (Map<String, ?>) map;
            return JsonObject.of(typed);
        }
        return JsonObject.empty();
    }

    private void bootstrapExtensions() {
        for (var ext : extensions) {
            for (var method : ext.methods()) {
                extensionMethodOwners.put(method, ext.extensionId());
            }
            extensionsById.put(ext.extensionId(), ext);
            if (ext.negotiation() == ExtensionNegotiation.OPTIONAL) {
                optionalNegotiationExtensionIds.add(ext.extensionId());
            }
            bootstrappingExtensionId = ext.extensionId();
            try {
                ext.bootstrap(this);
            } finally {
                bootstrappingExtensionId = null;
            }
        }
    }

    /**
     * Runs each extension's {@link ServerExtension#shutdown()} bounded by the shared shutdown
     * deadline, so a slow extension cannot make {@link #close()} run past the configured grace
     * period on top of whatever {@link OperationTracker#drain} already spent waiting on in-flight
     * requests. A slow extension is logged and left running in the background rather than blocked
     * on indefinitely.
     */
    private void shutdownExtensions(long deadlineNanos) {
        for (var ext : extensions) {
            final var worker = Thread.ofVirtual()
                    .name("ext-shutdown-" + ext.extensionId())
                    .start(() -> {
                        try {
                            ext.shutdown();
                        } catch (Exception e) {
                            logger.warn("Extension shutdown error: {}", ext.extensionId(), e);
                        }
                    });
            var remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000;
            if (remainingMs <= 0) {
                logger.warn("Shutdown grace period already elapsed, not waiting on extension: {}", ext.extensionId());
                continue;
            }
            try {
                worker.join(remainingMs);
                if (worker.isAlive()) {
                    logger.warn(
                            "Extension shutdown exceeded remaining grace period, continuing without waiting further: {}",
                            ext.extensionId());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    public List<ServerExtension> extensions() {
        return Collections.unmodifiableList(extensions);
    }

    @Override
    public @Nullable String extensionForMethod(String method) {
        return extensionMethodOwners.get(method);
    }

    @Override
    public boolean extensionRequiresMeta(String extensionId) {
        var ext = extensionsById.get(extensionId);
        return ext != null && ext.requiresMetaEnvelope();
    }

    @Override
    public boolean extensionNegotiationOptional(String extensionId) {
        return optionalNegotiationExtensionIds.contains(extensionId);
    }

    @Override
    public Tools tools() {
        return toolRegistry;
    }

    @Override
    public Resources resources() {
        return resourceRegistry;
    }

    @Override
    public Prompts prompts() {
        return promptRegistry;
    }

    @Override
    public Completions completions() {
        return completionRegistry;
    }

    @Override
    public Tasks tasks() {
        return taskRegistry;
    }

    @Override
    public TaskRegistry tasksRegistry() {
        return taskRegistry;
    }

    private final Notifications serverNotifications = this::broadcastLog;

    @Override
    public Notifications notifications() {
        return serverNotifications;
    }

    /**
     * Broadcasts a structured MCP log to every active session whose configured threshold admits the
     * level. No-op when the server does not advertise the {@code logging} capability. The wire form
     * is serialized once and reused across recipients.
     */
    public void broadcastLog(LoggingLevel level, @Nullable String logger, @Nullable Object data) {
        if (!config.capabilities().logging()) {
            return;
        }
        var mapper = Protocols.list().getFirst().responseMapper();
        var paramsStr = mapper.encode(mapper.loggingMessageParams(level, logger, data));
        var notificationJson = JsonRpcCodec.serializeNotificationAsString(NotificationLogSupport.LOG_METHOD, paramsStr);
        for (var session : sessionManager.allSessions()) {
            if (session.state() != SessionState.ACTIVE) {
                continue;
            }
            var configuredLevel = session.loggingLevel();
            var threshold = configuredLevel != null ? configuredLevel : LoggingLevel.INFO;
            if (level.ordinal() < threshold.ordinal()) {
                continue;
            }
            sendSerializedNotification(session, NotificationLogSupport.LOG_METHOD, paramsStr, notificationJson, null);
        }
    }

    @Override
    public Session createSession(String sessionId) {
        return sessionManager.createSession(sessionId);
    }

    @Override
    public Optional<Session> getSession(String sessionId) {
        return sessionManager.getSession(sessionId);
    }

    @Override
    public Optional<Session> getLocalSession(String sessionId) {
        return sessionManager.getLocalSession(sessionId);
    }

    @Override
    public void removeSession(String sessionId) {
        sessionManager.removeSession(sessionId);
    }

    SseEvent appendResponse(Session session, SessionEvent.ResponseEvent event) {
        var sseEventId = nextEventId();
        var enriched = new SessionEvent.ResponseEvent(
                event.sessionId(), event.requestId(), event.resultJson(), event.timestamp(), sseEventId, null);
        sessionEventStore.append(enriched);

        var sseEvent = new SseEvent(String.valueOf(sseEventId), "message", event.resultJson());

        session.send(sseEvent);
        return sseEvent;
    }

    @Override
    public void sendNotification(Session session, String method, Object params) {
        sendNotification(session, method, params, null);
    }

    @Override
    public void sendNotification(
            Session session, String method, @Nullable Object params, @Nullable OutboundSseStream stream) {
        var paramsStr = JsonRpcCodec.toJsonParams(params);
        var notificationJson = JsonRpcCodec.serializeNotificationAsString(method, paramsStr);
        sendSerializedNotification(session, method, paramsStr, notificationJson, stream);
    }

    private void sendSerializedNotification(
            Session session,
            String method,
            String paramsStr,
            String notificationJson,
            @Nullable OutboundSseStream stream) {
        if (session.state() == SessionState.CLOSED) {
            return;
        }
        var target = stream != null ? stream : outboundStreamResolver.resolve(session);
        var streamKey = target != null ? target.streamKey() : null;
        var sseEventId = nextEventId();
        var notificationEvent = new SessionEvent.NotificationEvent(
                session.id(), method, paramsStr, System.currentTimeMillis(), sseEventId, streamKey);
        sessionEventStore.append(notificationEvent);

        var sseEvent = new SseEvent(ServerEngine.wireEventId(sseEventId, streamKey), "message", notificationJson);

        if (target != null) {
            target.start();
            target.writeEvent(sseEvent);
        } else {
            session.send(sseEvent);
        }
    }

    @Override
    public CompletableFuture<String> sendRequest(Session session, String method, Object params) {
        return sendRequest(session, method, params, null);
    }

    /**
     * Sends a JSON-RPC request to a session and tracks its response.
     *
     * @param session the session that receives the request
     * @param method  the JSON-RPC method name
     * @param params  the request parameters
     * @param stream  the outbound stream to use, or {@code null} to resolve one for the session
     * @return a future completed with the response JSON, or completed exceptionally if the request fails
     */
    @Override
    public CompletableFuture<String> sendRequest(
            Session session, String method, Object params, @Nullable OutboundSseStream stream) {
        final var paramsStr = JsonRpcCodec.toJsonParams(params);
        final var requestId = RequestId.of(UUID.randomUUID().toString());
        final var future = new CompletableFuture<String>();

        final var requestJson = JsonRpcCodec.serializeRequestAsString(requestId, method, paramsStr);
        final var target = stream != null ? stream : outboundStreamResolver.resolve(session);
        registerPendingRequest(requestId, session.id(), target, future);
        final var streamKey = target != null ? target.streamKey() : null;
        final var sseEventId = nextEventId();

        final var outboundEvent = new SessionEvent.OutboundRequestEvent(
                session.id(), requestId, method, paramsStr, System.currentTimeMillis(), sseEventId, streamKey);
        sessionEventStore.append(outboundEvent);

        final var sseEvent = new SseEvent(ServerEngine.wireEventId(sseEventId, streamKey), "message", requestJson);

        if (target != null) {
            target.start();
            target.writeEvent(sseEvent);
        } else {
            logger.trace(
                    "sendRequest fallback session.send: method={}, session={}, conn={}",
                    method,
                    session.id(),
                    session.connection());
            session.send(sseEvent);
        }
        return future;
    }

    @Override
    public boolean completePendingRequest(
            @Nullable RequestId requestId, @Nullable String sessionId, @Nullable String channelId, String resultJson) {
        var entry = removePendingRequest(requestId, sessionId, channelId);
        return entry != null && entry.future().complete(resultJson);
    }

    @Override
    public boolean failPendingRequest(
            @Nullable RequestId requestId, @Nullable String sessionId, @Nullable String channelId, String message) {
        var entry = removePendingRequest(requestId, sessionId, channelId);
        return entry != null && entry.future().completeExceptionally(new RuntimeException(message));
    }

    private DefaultTachyonServer.@Nullable PendingRequestEntry removePendingRequest(
            @Nullable RequestId requestId, @Nullable String sessionId, @Nullable String channelId) {
        if (requestId == null) {
            return null;
        }
        var entry = pendingRequests.get(requestId);
        if (entry == null) {
            return null;
        }
        var expectedOwner = isStateless() ? entry.channelId() : entry.sessionId();
        var actualOwner = isStateless() ? channelId : sessionId;
        var matches = expectedOwner != null && actualOwner != null && expectedOwner.equals(actualOwner);
        if (!matches) {
            logger.warn(
                    "Pending request ownership mismatch: id={}, expectedSession={}, actualSession={}, expectedChannel={}, actualChannel={}",
                    requestId,
                    entry.sessionId(),
                    sessionId,
                    entry.channelId(),
                    channelId);
            return null;
        }
        return pendingRequests.remove(requestId, entry) ? entry : null;
    }

    @Override
    public void registerPendingRequest(
            RequestId requestId,
            @Nullable String sessionId,
            @Nullable OutboundSseStream stream,
            CompletableFuture<String> future) {
        var entry = new PendingRequestEntry(sessionId, stream != null ? stream.channelId() : null, future);
        pendingRequests.put(requestId, entry);
        var timeout = config.runtime().requestTimeout();
        future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
        future.whenComplete((res, ex) -> {
            if (pendingRequests.remove(requestId, entry)) {
                if (ex instanceof TimeoutException) {
                    logger.debug(
                            "Pending request timed out after {}s: id={}, pendingCount={}",
                            timeout.toSeconds(),
                            requestId,
                            pendingRequests.size());
                    if (logger.isTraceEnabled()) {
                        logger.trace("{}", threadDump());
                    }
                }
            }
        });
    }

    private static String threadDump() {
        var sb = new StringBuilder("Thread dump:\n");
        Thread.getAllStackTraces().forEach((t, frames) -> {
            sb.append("  ")
                    .append(t.getName())
                    .append(" [")
                    .append(t.getState())
                    .append("]");
            if (t.isVirtual()) sb.append(" (virtual)");
            sb.append('\n');
            for (var frame : frames) {
                sb.append("    at ").append(frame).append('\n');
            }
        });
        return sb.toString();
    }

    @Override
    public void appendEvent(SessionEvent event) {
        sessionEventStore.append(event);
    }

    @Override
    public List<SessionEvent> replay(String sessionId, long lastSeq) {
        return sessionEventStore.replay(sessionId, lastSeq);
    }

    @Override
    public long nextEventId() {
        return eventIdCounter.incrementAndGet();
    }

    void drainEvents(Session session) {
        if (!session.connection().isWritable()) {
            return;
        }

        var cursor = session.cursor();
        var lastIndex = sessionEventStore.drain(session.id(), cursor, event -> {
            if (!session.connection().isWritable()) {
                return false;
            }
            var sseEvent = ServerEngine.toSseEvent(event);
            if (sseEvent == null) return true;
            return session.send(sseEvent);
        });
        session.cursor(lastIndex);
    }

    Backpressure backpressure(Session session) {
        return session.computeBackpressure();
    }

    @Override
    public void close() {
        requireNotOnEventLoop(transport);
        lifecycleLock.lock();
        try {
            final var current = transport;
            // start() may have published `transport` while this call waited for the lock, so the
            // pre-lock check above can miss a caller now running on that server's event loop.
            requireNotOnEventLoop(current);
            if (closed) {
                return;
            }
            closed = true;
            if (current instanceof NettyServer netty) {
                netty.stopAccepting();
            }
            try {
                logger.info("Shutting down TachyonMCP Server");
                final var deadline = System.nanoTime()
                        + config.runtime().shutdownGracePeriod().toNanos();
                try {
                    operations.drain(deadline);
                    executor.shutdown();
                    if (!executor.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                        executor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    executor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
                subscriptionRegistry.closeAll();
                shutdownExtensions(deadline);
                taskRegistry.stopTtlJanitor();
                sessionManager.close();
                sessionEventStore.close();
            } catch (IOException e) {
                logger.debug("Error while shutting down", e);
            } finally {
                if (current instanceof NettyServer netty) {
                    netty.close();
                } else if (current != null) {
                    try {
                        current.close();
                    } catch (IOException e) {
                        logger.debug("Error closing transport", e);
                    }
                }
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    private static void requireNotOnEventLoop(@Nullable Closeable transport) {
        if (transport instanceof NettyServer netty && netty.inEventLoop()) {
            throw new IllegalStateException("close() must not be called from a Netty event loop thread — "
                    + "draining in-flight requests needs that thread to flush their responses. "
                    + "Call close() from another thread.");
        }
    }
}
