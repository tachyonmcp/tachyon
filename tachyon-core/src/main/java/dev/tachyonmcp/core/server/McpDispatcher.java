/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.runtime.AttributeKey;
import dev.tachyonmcp.api.server.domain.RequestId;
import dev.tachyonmcp.api.server.domain.ServerError;
import dev.tachyonmcp.api.server.session.SessionIdGenerator;
import dev.tachyonmcp.core.protocol.ProtocolResponseMapper;
import dev.tachyonmcp.core.protocol.Protocols;
import dev.tachyonmcp.core.protocol.RequestMappingException;
import dev.tachyonmcp.core.runtime.ChannelContext;
import dev.tachyonmcp.core.runtime.Session;
import dev.tachyonmcp.core.runtime.SessionState;
import dev.tachyonmcp.core.server.domain.ServerErrors;
import dev.tachyonmcp.core.server.features.subscriptions.SubscriptionStreamFailedException;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.server.observability.Observation;
import dev.tachyonmcp.core.server.observability.ObservationListener;
import dev.tachyonmcp.core.server.observability.OperationInfo;
import dev.tachyonmcp.core.server.observability.OperationKind;
import dev.tachyonmcp.core.server.observability.OperationOutcome;
import dev.tachyonmcp.core.server.session.DefaultDispatchContext;
import dev.tachyonmcp.core.server.session.DispatchContext;
import dev.tachyonmcp.core.server.session.SessionEvent;
import dev.tachyonmcp.core.transport.jsonrpc.JsonRpcCodec;
import dev.tachyonmcp.core.transport.jsonrpc.JsonRpcError;
import dev.tachyonmcp.core.transport.netty.McpInitializationHandler;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * Orchestrates the MCP server's per-request flow: parses JSON-RPC messages, establishes the session on
 * {@code initialize}, routes to registered handlers (including extension methods), tracks pending
 * requests, and encodes responses. Collaborator of {@link DefaultTachyonServer} — server holds state/registries,
 * this drives one request at a time.
 *
 * <p>MCP-specific but version-agnostic: it special-cases {@code initialize}/task-status and reaches
 * for models/codecs through the negotiated {@link dev.tachyonmcp.core.protocol.Protocol}, falling back
 * to {@link Protocols#baseline()} when no version was negotiated.
 */
@InternalApi
public class McpDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(McpDispatcher.class);

    private static final String METHOD_INITIALIZE = "initialize";
    private static final String METHOD_PING = "ping";

    /**
     * Interaction-context attribute key under which {@link McpInitializationHandler} stashes a
     * detached copy of the {@code initialize} HTTP request, so a custom
     * {@link SessionIdGenerator} can read its headers/URI.
     */
    public static final AttributeKey<HttpRequest> ATTR_INIT_REQUEST = AttributeKey.of("init.request");

    /**
     * Placeholder request for programmatic dispatch with no channel (the default generator ignores it).
     */
    private static final HttpRequest EMPTY_INIT_REQUEST =
            new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");

    public static final String NOTIFICATIONS_INITIALIZED = "notifications/initialized";
    private static final String NOTIFICATIONS_CANCELLED = "notifications/cancelled";

    private final Executor executor;

    private final ServerEngine server;

    private record InboundRequestKey(String sessionId, RequestId requestId) {}

    private final ConcurrentHashMap<InboundRequestKey, CompletableFuture<?>> inboundRequests =
            new ConcurrentHashMap<>();

    public McpDispatcher(ServerEngine server, Executor executor) {
        this.server = server;
        this.executor = executor;
    }

    /**
     * Decorates the per-channel context with the per-request MCP dispatch surface. Without a channel
     * context (direct invocation, tests), fresh channel state is created for the default protocol.
     */
    private DefaultDispatchContext dispatchContext(@Nullable ChannelContext channelContext) {
        return dispatchContext(channelContext, null);
    }

    private DefaultDispatchContext dispatchContext(@Nullable ChannelContext channelContext, @Nullable RequestId id) {
        var channel =
                channelContext != null ? channelContext : Protocols.baseline().createInteractionContext();
        return new DefaultDispatchContext(channel, server, id);
    }

    private List<ObservationListener> observationListeners() {
        return server.config().observability().listeners();
    }

    /**
     * Builds the {@link OperationInfo} for one operation, filling in the server's bound
     * address/port when already started (omitted for direct/programmatic dispatch, e.g. tests).
     */
    private OperationInfo newOperationInfo(
            OperationKind kind,
            String method,
            @Nullable RequestId id,
            @Nullable String sessionId,
            @Nullable Object params,
            @Nullable ChannelContext channelContext) {
        var builder = OperationInfo.builder(kind, method, id)
                .sessionId(sessionId)
                .traceparent(extractTraceParent(params))
                .protocolVersion(channelContext != null ? channelContext.protocolVersion() : null);
        try {
            builder.serverAddress(server.host()).serverPort(server.port());
        } catch (IllegalStateException e) {
            // Server not started (e.g. tests dispatching directly without start()).
        }
        return builder.build();
    }

    /**
     * Extracts {@code _meta.traceparent} from raw params, independent of payload-capture policy.
     *
     * @see <a href="https://github.com/open-telemetry/semantic-conventions-genai/blob/main/docs/gen-ai/mcp.md">
     * Semantic conventions for Model Context Protocol (MCP)</a>
     */
    private static @Nullable String extractTraceParent(@Nullable Object params) {
        if (params instanceof JsonNode node) {
            final var traceParent = node.path("_meta").path("traceparent");
            return traceParent.isString() ? traceParent.stringValue() : null;
        }
        if (params instanceof Map<?, ?> map
                && map.get("_meta") instanceof Map<?, ?> meta
                && meta.get("traceparent") instanceof String traceParent) {
            return traceParent;
        }
        return null;
    }

    public sealed interface DispatchResult
            permits DispatchResult.Accepted, DispatchResult.Response, DispatchResult.Status {

        record Accepted() implements DispatchResult {
            static final Accepted INSTANCE = new Accepted();
        }

        /**
         * A dispatched JSON-RPC response (result or error envelope) ready to write to the wire.
         *
         * @param responseBody the encoded JSON-RPC response body
         * @param sessionId    the session id to echo back, if any
         * @param httpStatus   the real HTTP response status; JSON-RPC errors default to {@code 200}
         *                     per the JSON-RPC-over-HTTP convention (see {@link
         *                     JsonRpcError}) — some protocol-level
         *                     errors override it (e.g. {@code 400}/{@code 404})
         */
        record Response(byte[] responseBody, @Nullable String sessionId, int httpStatus) implements DispatchResult {
            public String responseBodyString() {
                return new String(responseBody, StandardCharsets.UTF_8);
            }
        }

        /**
         * Transport-level signal: the transport must reply with a raw HTTP {@code code}/{@code message},
         * not a JSON-RPC error envelope. Used for conditions the MCP Streamable HTTP spec ties to a
         * specific HTTP status rather than a JSON-RPC error code — e.g. a missing {@code MCP-Session-Id}
         * header (400) or an unknown/expired session (404).
         *
         * @param code    the HTTP status code
         * @param message the HTTP status message
         */
        record Status(int code, String message) implements DispatchResult {}
    }

    /**
     * Parses a POST body, classifying a malformed one the same way a peek upstream would have, so
     * the answer does not depend on whether a validation handler already looked inside it.
     *
     * @param body the body to parse
     * @return the parse outcome
     */
    public JsonRpcCodec.Parse parseBody(ByteBuf body) {
        var parse = JsonRpcCodec.tryParseRequest(body);
        if (parse.message() == null) {
            logger.debug("Failed to parse JSON-RPC message, invalidRequest={}", parse.invalidRequest());
        }
        return parse;
    }

    public byte[] parseError(@Nullable ChannelContext channelContext) {
        var mapper = channelContext != null
                ? channelContext.protocol().responseMapper()
                : dispatchContext(null).responseMapper();
        return encodeError(null, ServerErrors.parseError(), mapper);
    }

    /**
     * Like {@link #parseError}, for a body that parsed as valid JSON but not a valid JSON-RPC
     * envelope — {@code -32600} rather than {@code -32700}.
     */
    public byte[] invalidRequestError(@Nullable ChannelContext channelContext) {
        var mapper = channelContext != null
                ? channelContext.protocol().responseMapper()
                : dispatchContext(null).responseMapper();
        return encodeError(null, ServerErrors.invalidRequest("Invalid Request"), mapper);
    }

    /**
     * Encodes the error a body that produced no message earns: {@code -32600} when it was valid
     * JSON in the wrong shape, {@code -32700} when it was not valid JSON at all.
     *
     * @param invalidRequest the classification carried by {@link JsonRpcCodec.Parse}
     * @param channelContext the channel's context, or {@code null} before one is bound
     * @return the encoded JSON-RPC error
     */
    public byte[] malformedBodyError(boolean invalidRequest, @Nullable ChannelContext channelContext) {
        return invalidRequest ? invalidRequestError(channelContext) : parseError(channelContext);
    }

    public CompletableFuture<DispatchResult> dispatchRequestAsync(
            RequestId id, String method, Object params, @Nullable String sessionId) {
        return dispatchRequestAsync(id, method, params, sessionId, null, null);
    }

    public CompletableFuture<DispatchResult> dispatchRequestAsync(
            RequestId id,
            String method,
            Object params,
            @Nullable String sessionId,
            @Nullable OutboundSseStream outboundSseStream,
            @Nullable ChannelContext channelContext) {
        return dispatchRequestAsync(
                id,
                method,
                params,
                sessionId,
                outboundSseStream,
                channelContext,
                CompletableFuture.completedFuture(null));
    }

    /** Dispatches a request and retains its shutdown admission until the transport finishes writing. */
    public CompletableFuture<DispatchResult> dispatchRequestAsync(
            RequestId id,
            String method,
            Object params,
            @Nullable String sessionId,
            @Nullable OutboundSseStream outboundSseStream,
            @Nullable ChannelContext channelContext,
            CompletableFuture<Void> transportCompletion) {
        return server.operations()
                .execute(
                        () -> dispatchTrackedRequestAsync(
                                id, method, params, sessionId, outboundSseStream, channelContext),
                        transportCompletion);
    }

    private CompletableFuture<DispatchResult> dispatchTrackedRequestAsync(
            RequestId id,
            String method,
            Object params,
            @Nullable String sessionId,
            @Nullable OutboundSseStream outboundSseStream,
            @Nullable ChannelContext channelContext) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(method, "method");

        logger.trace("Dispatching request for method {}", method);

        var kind = METHOD_INITIALIZE.equals(method) ? OperationKind.INITIALIZE : OperationKind.REQUEST;
        var info = newOperationInfo(kind, method, id, sessionId, params, channelContext);
        var observation = Observation.start(observationListeners(), info);

        var requestCtx = dispatchContext(channelContext, id);
        requestCtx.setObservation(observation);
        try {
            requestCtx.setPermittedLogLevel(requestCtx.requestMapper().permittedLogLevel(params));
        } catch (RequestMappingException e) {
            return CompletableFuture.completedFuture(rejected(id, e.error(), requestCtx));
        }
        if (METHOD_INITIALIZE.equals(method)) {
            if (sessionId == null) {
                return dispatchInitializeAsync(id, params, requestCtx, channelContext);
            }
            return CompletableFuture.completedFuture(
                    rejected(id, ServerErrors.invalidRequest("Session already initialized"), requestCtx));
        }

        if (server.isStateless()
                || !requestCtx.protocol().supportsSessions()
                || (sessionId == null && METHOD_PING.equals(method))) {

            requestCtx.setOutboundStream(outboundSseStream);
            var handler = server.getHandler(method);
            if (handler == null) {
                return CompletableFuture.completedFuture(
                        rejected(id, ServerErrors.methodNotFound("Method not found"), requestCtx));
            }
            var negotiationRejection = extensionNegotiationRejection(method, requestCtx);
            if (negotiationRejection != null) {
                return CompletableFuture.completedFuture(rejected(id, negotiationRejection, requestCtx));
            }
            return invokeHandlerAsync(id, method, params, outboundSseStream, requestCtx, null, handler);
        }

        if (sessionId == null) {
            observation.closeStart();
            observation.complete(new OperationOutcome.Rejected(null, 400, 0));
            return CompletableFuture.completedFuture(new DispatchResult.Status(400, "Missing MCP-Session-Id header"));
        }

        var sessionOpt = server.getSession(sessionId);
        if (sessionOpt.isEmpty()) {
            observation.closeStart();
            observation.complete(new OperationOutcome.Rejected(null, 404, 0));
            return CompletableFuture.completedFuture(new DispatchResult.Status(404, "Unknown session"));
        }
        var session = sessionOpt.get();
        session.touch();

        requestCtx.setSession(session);
        requestCtx.setOutboundStream(outboundSseStream);

        var sessionState = session.state();
        if (sessionState == SessionState.CLOSED) {
            return CompletableFuture.completedFuture(
                    rejected(id, ServerErrors.invalidRequest("Session is closed"), requestCtx));
        }
        if (sessionState == SessionState.INITIALIZING && !METHOD_PING.equals(method)) {
            return CompletableFuture.completedFuture(rejected(
                    id, ServerErrors.invalidRequest("Session is not yet active, only ping allowed"), requestCtx));
        }

        var handler = server.getHandler(method);
        if (handler == null) {
            return CompletableFuture.completedFuture(
                    rejected(id, ServerErrors.methodNotFound("Method not found"), requestCtx));
        }
        var negotiationRejection = extensionNegotiationRejection(method, requestCtx);
        if (negotiationRejection != null) {
            return CompletableFuture.completedFuture(rejected(id, negotiationRejection, requestCtx));
        }

        return invokeHandlerAsync(id, method, params, outboundSseStream, requestCtx, session, handler);
    }

    /**
     * Enforces {@link dev.tachyonmcp.api.server.extensions.ServerExtension#negotiation()} for a method
     * that already resolved to a handler, so unknown methods stay {@code methodNotFound}. Declaration is
     * read from the context: the session under 2025-11-25, the per-request channel context under
     * 2026-07-28.
     */
    private @Nullable ServerError extensionNegotiationRejection(String method, DispatchContext ic) {
        var owningExtensionId = server.extensionForMethod(method);
        if (owningExtensionId == null || server.extensionNegotiationOptional(owningExtensionId)) {
            return null;
        }
        if (!ic.isExtensionEnabled(owningExtensionId)) {
            logger.debug("Extension {} not declared by client for method {}", owningExtensionId, method);
            return ServerErrors.missingRequiredExtension(owningExtensionId);
        }
        return null;
    }

    private <I, O> CompletableFuture<DispatchResult> invokeHandlerAsync(
            RequestId id,
            String method,
            Object rawParams,
            @Nullable OutboundSseStream outboundSseStream,
            DispatchContext context,
            @Nullable Session session,
            RpcMethodHandler<I, O> handler) {
        var paramsStr = rawParams instanceof Map || rawParams instanceof List
                ? JsonRpcCodec.writeValueAsString(rawParams)
                : rawParams instanceof String s ? s : rawParams instanceof JsonNode n ? n.toString() : null;

        // Closed here, on the calling thread, because the work below runs on the executor --
        // reattach() re-opens it there for the decode+kickoff phase.
        context.observation().closeStart();

        final var completion = new CompletableFuture<O>();
        final var key = session != null ? new InboundRequestKey(session.id(), id) : null;
        if (key != null) {
            if (inboundRequests.putIfAbsent(key, completion) != null) {
                return CompletableFuture.completedFuture(
                        rejected(id, ServerErrors.invalidRequest("Request ID already in flight"), context));
            }
        }
        final var task = new FutureTask<Void>(() -> {
            try {
                final var startNs = System.nanoTime();
                logger.debug("Handler start: method={}, id={}", method, id);
                if (session != null) {
                    server.appendEvent(new SessionEvent.RequestEvent(
                            session.id(), id, method, paramsStr, System.currentTimeMillis()));
                }
                final var m = server.config().observability();
                final var watchdog = m.slowRequestLogging()
                        ? HandlerWatchdog.watch(
                                method, id, startNs, m.slowRequestThreshold().toMillis())
                        : CompletableFuture.completedFuture(null);
                completion.whenComplete((result, error) -> watchdog.cancel(false));
                final var reattached = context.observation().reattach();
                CompletionStage<O> stage;
                try {
                    stage = OutboundSseStreamMessageRouter.withDispatchContext(
                            session != null ? session.id() : null,
                            outboundSseStream,
                            () -> decodeAndHandleAsync(handler, context, rawParams));
                } catch (Throwable e) {
                    stage = CompletableFuture.failedFuture(e);
                } finally {
                    context.observation().closeReattached(reattached);
                }
                final var handlerStage = stage;
                completion.whenComplete((result, error) -> {
                    if (completion.isCancelled())
                        handlerStage.toCompletableFuture().cancel(true);
                });
                handlerStage.whenComplete((result, error) -> {
                    if (error != null) completion.completeExceptionally(error);
                    else completion.complete(result);
                });
            } catch (Throwable e) {
                completion.completeExceptionally(e);
            }
            return null;
        });
        completion.whenComplete((result, error) -> {
            if (completion.isCancelled()) task.cancel(true);
        });
        try {
            executor.execute(task);
        } catch (RuntimeException e) {
            completion.completeExceptionally(e);
        }
        return completion
                .whenComplete((result, error) -> {
                    if (key != null) inboundRequests.remove(key, completion);
                })
                .handle((result, ex) -> {
                    if (ex != null) {
                        return handleHandlerError(id, method, ex, context);
                    }
                    return handleSuccessOrError(id, method, result, null, context);
                });
    }

    /**
     * Fixed decode-then-handle skeleton every dispatch path shares -- the single call site each
     * routes through, and the seam a future interceptor/chain wraps around.
     */
    private <I, O> CompletionStage<O> decodeAndHandleAsync(
            RpcMethodHandler<I, O> handler, DispatchContext context, @Nullable Object rawParams) {
        try {
            I decoded = handler.decode(context, rawParams);
            return handler.handleAsync(context, decoded);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private DispatchResult handleHandlerError(RequestId id, String method, Throwable ex, DispatchContext context) {
        var observation = context.observation();
        var reattached = observation.reattach();
        DispatchResult dispatchResult;
        OperationOutcome outcome;
        var exceptionDetail =
                context.engine().config().observability().payloadCapture().exceptionDetail();
        try {
            var unwrapped = ex instanceof CompletionException ce && ce.getCause() != null ? ce.getCause() : ex;
            if (unwrapped instanceof CancellationException) {
                logger.debug("Handler cancelled: method={}, id={}", method, id);
                dispatchResult = errorResult(id, ServerErrors.internalError("Internal error"), context);
                outcome = new OperationOutcome.Cancelled();
            } else if (unwrapped instanceof SubscriptionStreamFailedException sfe) {
                logger.debug("Subscription stream failed: method={}, id={}", method, id, sfe.getCause());
                dispatchResult = errorResult(id, ServerErrors.internalError("Internal error"), context);
                outcome = new OperationOutcome.StreamFailed(
                        sfe.getCause().getClass().getName(), exceptionDetail ? sfe.getCause() : null);
            } else if (unwrapped instanceof RequestMappingException rme) {
                logger.debug("Request mapping failed: method={}, id={}: {}", method, id, rme.getMessage());
                var error = rme.error();
                dispatchResult = errorResult(id, error, context);
                outcome = new OperationOutcome.HandlerFailed(
                        error, context.responseMapper().error(error).code(), exceptionDetail ? unwrapped : null);
            } else {
                logger.warn("Handler exception: method={}, id={}: {}", method, id, unwrapped.getMessage(), unwrapped);
                var error = ServerErrors.fromUnhandledException(unwrapped, "Internal error");
                dispatchResult = errorResult(id, error, context);
                outcome = new OperationOutcome.HandlerFailed(
                        error, context.responseMapper().error(error).code(), exceptionDetail ? unwrapped : null);
            }
        } finally {
            observation.closeReattached(reattached);
        }
        observation.complete(outcome);
        return dispatchResult;
    }

    private <O> DispatchResult handleSuccessOrError(
            RequestId id, String method, O result, @Nullable String sessionId, DispatchContext context) {
        var observation = context.observation();
        var reattached = observation.reattach();
        DispatchResult dispatchResult;
        OperationOutcome outcome;
        try {
            if (result instanceof ServerError error) {
                logger.debug("Handler error for {}: {}", method, error.message());
                dispatchResult = errorResult(id, error, context);
                outcome = new OperationOutcome.HandlerFailed(
                        error,
                        context.responseMapper().error(error).code(),
                        context.observation().info().exceptionCause());
            } else {
                var exceptionDetail = context.engine()
                        .config()
                        .observability()
                        .payloadCapture()
                        .exceptionDetail();
                var body = encodeResponse(id, result, context.responseMapper(), observation, exceptionDetail);
                dispatchResult = new DispatchResult.Response(body, sessionId, 200);
                outcome = new OperationOutcome.Completed();
            }
        } finally {
            observation.closeReattached(reattached);
        }
        observation.complete(outcome);
        return dispatchResult;
    }

    public DispatchResult dispatchNotification(String method, @Nullable Object params, @Nullable String sessionId) {
        return dispatchNotification(method, params, sessionId, null);
    }

    public DispatchResult dispatchNotification(
            String method,
            @Nullable Object params,
            @Nullable String sessionId,
            @Nullable ChannelContext channelContext) {
        var info = newOperationInfo(OperationKind.NOTIFICATION, method, null, sessionId, params, channelContext);
        var observation = Observation.start(observationListeners(), info);
        observation.closeStart();

        if (server.isStateless()) {
            logger.debug("Stateless notification ignored: {}", method);
            observation.complete(new OperationOutcome.NotificationIgnored());
            return DispatchResult.Accepted.INSTANCE;
        }
        var context = dispatchContext(channelContext);
        context.setObservation(observation);

        final Optional<Session> sessionOpt;
        if (sessionId != null) {
            sessionOpt = server.getSession(sessionId);
            sessionOpt.ifPresent(Session::touch);
        } else {
            sessionOpt = Optional.empty();
        }
        switch (method) {
            case NOTIFICATIONS_INITIALIZED -> {
                logger.info("Client initialized notification received");
                sessionOpt.ifPresent(session -> {
                    if (session.activate()) {
                        logger.info("Session activated: {}", sessionId);
                    }
                });
                observation.complete(new OperationOutcome.NotificationAccepted());
                return DispatchResult.Accepted.INSTANCE;
            }
            case NOTIFICATIONS_CANCELLED -> {
                handleCancellation(context, params, sessionId);
                observation.complete(new OperationOutcome.NotificationAccepted());
                return DispatchResult.Accepted.INSTANCE;
            }
            default -> {}
        }
        logger.debug("Unhandled notification: {}", method);
        observation.complete(new OperationOutcome.NotificationIgnored());
        return DispatchResult.Accepted.INSTANCE;
    }

    private void handleCancellation(DispatchContext context, @Nullable Object params, @Nullable String sessionId) {
        var cancellation = context.requestMapper().cancellation(params);
        if (cancellation == null) {
            logger.debug("Cancellation notification missing requestId");
            return;
        }
        if (sessionId == null) {
            logger.debug("Cancellation without session, requestId={}", cancellation.requestId());
            return;
        }
        server.getSession(sessionId)
                .ifPresentOrElse(
                        session -> {
                            final var inbound =
                                    inboundRequests.get(new InboundRequestKey(sessionId, cancellation.requestId()));
                            final var cancelled = inbound != null && inbound.cancel(true);
                            logger.debug(
                                    "Cancellation received: requestId={}, sessionId={}, reason={}, pending={}",
                                    cancellation.requestId(),
                                    sessionId,
                                    cancellation.reason(),
                                    cancelled);
                            server.appendEvent(new SessionEvent.CancelEvent(
                                    sessionId, cancellation.requestId(), System.currentTimeMillis()));
                        },
                        () -> logger.debug(
                                "Cancellation for unknown session: {}, requestId={}",
                                sessionId,
                                cancellation.requestId()));
    }

    private CompletableFuture<DispatchResult> dispatchInitializeAsync(
            RequestId id, Object rawParams, DispatchContext ic, @Nullable ChannelContext channelContext) {
        logger.debug("Client initialize: id={} stateless={}", id, server.isStateless());
        var handler = server.getHandler("initialize");
        if (handler == null) {
            return CompletableFuture.completedFuture(
                    rejected(id, ServerErrors.methodNotFound("Method not found: initialize"), ic));
        }
        // Stateful init creates the session before invoking the handler; stateless skips it. Both
        // then share one async pipeline — the response sessionId falls out of ic.session() (null
        // when stateless, since no session was set).
        // Closed here, on the calling thread, because the work below runs on the executor --
        // reattach() re-opens it there for the decode+kickoff phase.
        ic.observation().closeStart();
        return CompletableFuture.supplyAsync(
                        () -> {
                            var reattached = ic.observation().reattach();
                            try {
                                if (!server.isStateless()) {
                                    final var session = server.createSession(generateSessionId(channelContext));
                                    session.securityContext(ic.securityContext());
                                    ic.setSession(session);
                                }
                                return (CompletionStage<Object>) decodeAndHandleAsync(handler, ic, rawParams);
                            } catch (Exception e) {
                                return CompletableFuture.failedFuture(e);
                            } finally {
                                ic.observation().closeReattached(reattached);
                            }
                        },
                        executor)
                .thenCompose(stage -> stage)
                .handle((result, ex) -> {
                    if (ex != null) {
                        return handleHandlerError(id, "initialize", ex, ic);
                    }
                    final var session = ic.session();
                    var sessionId = session != null ? session.id() : null;
                    ic.observation().info().sessionId(sessionId);
                    return handleSuccessOrError(id, "initialize", result, sessionId, ic);
                });
    }

    private DispatchResult errorResult(RequestId id, ServerError error, DispatchContext context) {
        var wireError = context.responseMapper().error(error);
        var body = JsonRpcCodec.serializeError(id, wireError.code(), wireError.message(), wireError.data());
        return new DispatchResult.Response(body, null, wireError.httpStatus());
    }

    /** Like {@link #errorResult}, for a rejection decided before any handler ran — completes observation as {@code Rejected}. */
    private DispatchResult rejected(RequestId id, ServerError error, DispatchContext context) {
        var observation = context.observation();
        observation.closeStart();
        var wireError = context.responseMapper().error(error);
        observation.complete(new OperationOutcome.Rejected(error, wireError.httpStatus(), wireError.code()));
        var body = JsonRpcCodec.serializeError(id, wireError.code(), wireError.message(), wireError.data());
        return new DispatchResult.Response(body, null, wireError.httpStatus());
    }

    private static byte[] encodeResponse(
            RequestId id,
            Object result,
            ProtocolResponseMapper mapper,
            Observation observation,
            boolean exceptionDetail) {
        if (result instanceof String s) {
            return JsonRpcCodec.serializeResponse(id, s);
        }
        try {
            var resultJson = mapper.encode(result);
            return JsonRpcCodec.serializeResponse(id, resultJson);
        } catch (Exception e) {
            logger.error(
                    "JSON serialization failed for {}: {}", result.getClass().getSimpleName(), e.getMessage(), e);
            observation.markSerializationFailed(e, exceptionDetail);
            return encodeError(id, ServerErrors.internalError("Failed to encode response"), mapper);
        }
    }

    private static byte[] encodeError(@Nullable RequestId id, ServerError error, ProtocolResponseMapper mapper) {
        var wireError = mapper.error(error);
        return JsonRpcCodec.serializeError(id, wireError.code(), wireError.message(), wireError.data());
    }

    private String generateSessionId(@Nullable ChannelContext channelContext) {
        final var request =
                channelContext != null ? channelContext.get(ATTR_INIT_REQUEST).orElse(null) : null;
        final var generator = server.sessionIdGenerator();
        final var id = generator.generate(channelContext, request != null ? request : EMPTY_INIT_REQUEST);
        if (id == null || id.isBlank()) {
            throw new IllegalStateException("SessionIdGenerator produced a blank session id");
        }
        return id;
    }
}
