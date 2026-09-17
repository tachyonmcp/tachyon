/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty;

import dev.tachyonmcp.core.protocol.Protocols;
import dev.tachyonmcp.core.server.McpDispatcher;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.transport.netty.http.AcceptValidationHandler;
import dev.tachyonmcp.core.transport.netty.http.DnsRebindingProtectionHandler;
import dev.tachyonmcp.core.transport.netty.http.EndpointValidatorHandler;
import dev.tachyonmcp.core.transport.netty.http.McpHeaderGuardHandler;
import dev.tachyonmcp.core.transport.netty.http.McpHeaderMatchHandler;
import dev.tachyonmcp.core.transport.netty.http.StatelessValidatorHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerKeepAliveHandler;
import io.netty.handler.codec.http.cors.CorsConfig;
import io.netty.handler.codec.http.cors.CorsHandler;
import io.netty.handler.flush.FlushConsolidationHandler;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.IdleStateHandler;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Assembles the Netty pipeline for each new MCP channel: validation handlers
 * (endpoint, origin, protocol version, accept header, stateless guard),
 * {@link InteractionHandler}, HTTP aggregation, idle timeout, each registered
 * {@link dev.tachyonmcp.core.protocol.Protocol}'s own
 * {@link dev.tachyonmcp.core.protocol.Protocol#requestHandlers request handlers}, the
 * initialization-phase handler ({@link McpInitializationHandler}), and the
 * {@link LifecyclePipelineCoordinator}.
 *
 * <p>In stateless mode, {@link StatelessValidatorHandler} is added to reject
 * session-related headers and DELETE methods before they reach protocol handlers.
 */
@ChannelHandler.Sharable
public class McpChannelInitializer extends ChannelInitializer<SocketChannel> {

    /** Default max aggregated request body. 64 KB was too small for schemas + tool results. */
    public static final int DEFAULT_MAX_CONTENT_LENGTH = 1024 * 1024; // 1 MB

    private static final String CHANNEL_LOGGER_NAME = "me.kpavlov.tachyon.transport.netty.channel";
    private static final LoggingHandler CHANNEL_LOGGER = new LoggingHandler(CHANNEL_LOGGER_NAME, LogLevel.DEBUG);
    private static final boolean CHANNEL_LOGGING_ENABLED =
            org.slf4j.LoggerFactory.getLogger(CHANNEL_LOGGER_NAME).isDebugEnabled();
    private static final int FLUSH_AFTER = FlushConsolidationHandler.DEFAULT_EXPLICIT_FLUSH_AFTER_FLUSHES;

    private final Duration readerIdleTimeout;
    private final Duration writerIdleTimeout;
    private final int maxContentLength;
    private final ProtocolVersionHandler protocolVersionHandler;
    private static final UnsupportedProtocolVersionHandler UNSUPPORTED_PROTOCOL_VERSION_HANDLER =
            new UnsupportedProtocolVersionHandler();
    private final AcceptValidationHandler acceptHeaderValidator;
    private final boolean stateless;
    private final EndpointValidatorHandler endpointValidatorHandler;
    private final InteractionHandler interactionHandler;
    private final McpHeaderMatchHandler headerMatchHandler;
    // Per-server, not static: carries this server's allowedHosts allowlist (DNS-rebinding protection).
    private final DnsRebindingProtectionHandler dnsRebindingHandler;

    @Nullable
    private final CorsConfig corsConfig;

    private static final ChannelHandler statelessValidator = new StatelessValidatorHandler();

    // Built once per server from every registered Protocol's own requestHandlers(server) — see
    // Protocol#requestHandlers. Per-server, not static: several of these handlers are bound to this
    // server's own tool registry / registered extensions.
    private final Map<String, ChannelHandler> protocolRequestHandlers;

    private final ServerEngine server;
    private final McpDispatcher dispatcher;

    @Nullable
    private final Consumer<ChannelPipeline> pipelineCustomizer;

    private final ChannelGroup childChannels;

    public McpChannelInitializer(
            String endpointPath,
            boolean stateless,
            ServerEngine server,
            Duration readerIdleTimeout,
            Duration writerIdleTimeout,
            int maxContentLength,
            ChannelGroup childChannels,
            @Nullable CorsConfig corsConfig,
            @Nullable List<String> allowedHosts,
            @Nullable Consumer<ChannelPipeline> pipelineCustomizer) {
        this.stateless = stateless;
        this.server = server;
        this.readerIdleTimeout = readerIdleTimeout;
        this.writerIdleTimeout = writerIdleTimeout;
        this.maxContentLength = maxContentLength;
        this.corsConfig = corsConfig;
        this.dnsRebindingHandler = allowedHosts == null
                ? new DnsRebindingProtectionHandler()
                : new DnsRebindingProtectionHandler(allowedHosts);
        this.pipelineCustomizer = pipelineCustomizer;
        this.childChannels = childChannels;
        this.dispatcher = new McpDispatcher(server, server.executor());
        this.interactionHandler = new InteractionHandler();
        this.headerMatchHandler = new McpHeaderMatchHandler(server);

        var handlers = new LinkedHashMap<String, ChannelHandler>();
        for (var protocol : Protocols.list()) {
            for (var handler : protocol.requestHandlers(server)) {
                handlers.put(
                        protocol.familyName() + "-" + protocol.versionString() + "-"
                                + handler.getClass().getSimpleName(),
                        handler);
            }
        }
        this.protocolRequestHandlers = handlers;

        protocolVersionHandler = new ProtocolVersionHandler(endpointPath);
        acceptHeaderValidator = new AcceptValidationHandler(endpointPath);
        endpointValidatorHandler = new EndpointValidatorHandler(endpointPath);
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        childChannels.add(ch);
        final var p = ch.pipeline();
        p.addFirst("flush", new FlushConsolidationHandler(FLUSH_AFTER, true));
        if (CHANNEL_LOGGING_ENABLED) {
            p.addLast("logger", CHANNEL_LOGGER);
        }
        p.addLast("http", new HttpServerCodec());

        // SessionTouchHandler is installed lazily at session-bind time (see SessionTouchHandler#install).
        // During initialization, no session is bound to the channel, so no touch is needed.

        // Idiomatic keep-alive management: inspects each request's Connection intent and either
        // keeps the socket alive or appends `Connection: close` and closes after the final content.
        // Placed right after the codec so it governs responses from ALL downstream handlers
        // (validation handlers write before the aggregator; protocol handlers write after it).
        p.addLast("http-keep-alive", new HttpServerKeepAliveHandler());
        p.addLast("dns-rebinding", dnsRebindingHandler);
        if (corsConfig != null) {
            p.addLast("cors", new CorsHandler(corsConfig));
        }
        p.addLast("mcp-endpoint", endpointValidatorHandler);

        // Must precede "protocol-version": a repeated version header would otherwise negotiate on its
        // first value while an intermediary routes on the last.
        p.addLast("mcp-header-guard", McpHeaderGuardHandler.INSTANCE);
        p.addLast("protocol-version", protocolVersionHandler);
        p.addLast("accept-header", acceptHeaderValidator);
        if (stateless) {
            p.addLast("stateless-mcp", statelessValidator);
        }

        // HttpObjectAggregator owns `Expect: 100-continue`: it answers 100 Continue for
        // acceptable requests and rejects oversized ones (413/417) before the body is
        // transferred. A separate HttpServerExpectContinueHandler would defeat that by
        // always acking 100 Continue upstream of the aggregator.
        p.addLast("http-aggregator", new HttpObjectAggregator(maxContentLength));

        // Rejects requests ProtocolVersionHandler flagged as an unsupported protocol version, now
        // that the body (and its JSON-RPC id) is available. Placed before "interaction" so a
        // rejected request never pays for the (redundant, since ProtocolVersionHandler already
        // tried and failed) protocol re-resolution InteractionHandler does as a GET/DELETE fallback.
        p.addLast("unsupported-protocol-version", UNSUPPORTED_PROTOCOL_VERSION_HANDLER);

        // FullHttpRequest is still an HttpRequest, so this fallback protocol resolution (for GET/
        // DELETE, which ProtocolVersionHandler doesn't cover) works the same post-aggregation.
        p.addLast("interaction", interactionHandler);
        // On a plain HTTP keep-alive socket an idle tick closes the connection. On a channel carrying
        // an open SSE stream the SseHeartbeat scheduler drives heartbeats independently, so idle ticks
        // are a no-op for SSE channels. Lower readerIdleTimeout below any intermediary proxy's idle
        // timeout (commonly 60s) to keep non-SSE keep-alive sockets from being reaped.
        if (!readerIdleTimeout.isZero() || !writerIdleTimeout.isZero()) {
            p.addLast(
                    "idle",
                    new IdleStateHandler(
                            readerIdleTimeout.toMillis(), writerIdleTimeout.toMillis(), 0, TimeUnit.MILLISECONDS));
        }

        // Version-independent: a SEP-2243 header mirror that is present must agree with the body on
        // every protocol version. Runs ahead of the per-version handlers below so the rule every
        // revision shares is settled first, and a mirror that lies is reported before one that is
        // merely missing (2026-07-28's RequiredHeadersHandler).
        p.addLast("mcp-header-match", headerMatchHandler);

        // Every registered Protocol's own request handlers (see Protocol#requestHandlers), e.g.
        // request-shape validation and, for 2026-07-28, required header mirrors and per-request
        // extension negotiation. Each checks the negotiated protocol on the interaction context and
        // no-ops for any other version, so all of them can sit unconditionally ahead of dispatch —
        // no dynamic pipeline surgery, since a channel's negotiated protocol isn't fixed at
        // construction time (a keep-alive connection can carry requests for different negotiated
        // versions, e.g. behind a proxy that pools upstream connections across unrelated clients).
        // Needs the aggregated body (peeked read-only), so it must run after http-aggregator and
        // before the phase handlers below.
        protocolRequestHandlers.forEach(p::addLast);

        // Both stateless and stateful modes go through the initialization phase handler.
        // It negotiates the protocol, fires InteractionEvent.OperationStarted, then the
        // LifecyclePipelineCoordinator replaces it with McpOperationHandler.
        var manager = new McpHandlerManager(server, dispatcher);
        p.addLast(manager.initHandlerName(), new McpInitializationHandler(server, dispatcher, server.executor()));
        p.addLast("lifecycle", new LifecyclePipelineCoordinator(manager));

        if (pipelineCustomizer != null) {
            pipelineCustomizer.accept(p);
        }
    }
}
