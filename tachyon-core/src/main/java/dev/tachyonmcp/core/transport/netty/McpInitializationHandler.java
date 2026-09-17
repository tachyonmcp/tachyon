/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty;

import static dev.tachyonmcp.core.transport.netty.ChannelHandlerUtils.captureInitRequest;
import static dev.tachyonmcp.core.transport.netty.ChannelHandlerUtils.completeOn;
import static dev.tachyonmcp.core.transport.netty.ChannelHandlerUtils.isRefused;
import static dev.tachyonmcp.core.transport.netty.ChannelHandlerUtils.sendAccepted;
import static dev.tachyonmcp.core.transport.netty.ChannelHandlerUtils.sendAcceptedAsync;
import static dev.tachyonmcp.core.transport.netty.ChannelHandlerUtils.sendPlainTextAndClose;
import static dev.tachyonmcp.core.transport.netty.ChannelHandlerUtils.sendResponseAndClose;
import static dev.tachyonmcp.core.transport.netty.McpResponseWriter.sendJsonResponse;
import static dev.tachyonmcp.core.transport.netty.McpResponseWriter.sendOptions;

import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.domain.RequestId;
import dev.tachyonmcp.core.protocol.mcp.McpHeaderNames;
import dev.tachyonmcp.core.runtime.InteractionEvent;
import dev.tachyonmcp.core.server.McpDispatcher;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.transport.jsonrpc.JsonRpcMessage;
import dev.tachyonmcp.core.transport.netty.sse.PostSseStream;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.timeout.IdleStateEvent;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles the INITIALIZATION lifecycle phase of an MCP connection.
 * <p>
 * Receives the first HTTP request on each new channel. If it is an
 * {@code initialize} JSON-RPC request (no {@code MCP-Session-Id} header),
 * the handler dispatches it, passes the resulting session via
 * {@link InteractionEvent.OperationStarted} to bind it into
 * {@link InteractionHandler}'s
 * {@link InteractionContext}, and fires
 * an event to transition the pipeline to the OPERATION phase.
 * <p>
 * All other requests (requests that already carry a session-id, GET, DELETE,
 * OPTIONS, pre-session ping) are immediately forwarded to a new
 * {@link McpOperationHandler} which handles them according to normal operation
 * semantics (including proper rejection of unknown sessions).
 * <p>
 * Not {@code @Sharable} — a fresh instance is added per channel by
 * {@link McpChannelInitializer}.
 */
public class McpInitializationHandler extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(McpInitializationHandler.class);
    private static final String METHOD_INITIALIZE = "initialize";

    private final ServerEngine server;
    private final McpDispatcher dispatcher;
    private final Executor executor;

    public McpInitializationHandler(ServerEngine server, McpDispatcher dispatcher, Executor executor) {
        this.server = server;
        this.dispatcher = dispatcher;
        this.executor = executor;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof FullHttpRequest req) {
            try {
                handleRequest(ctx, req);
            } finally {
                req.release();
            }
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    private void handleRequest(ChannelHandlerContext ctx, FullHttpRequest req) {
        var httpMethod = req.method();
        var origin = req.headers().get(HttpHeaderNames.ORIGIN);

        if (httpMethod == HttpMethod.OPTIONS) {
            sendOptions(ctx, origin);
            return;
        }

        if (httpMethod == HttpMethod.POST) {
            var sessionId = req.headers().get(McpHeaderNames.MCP_SESSION_ID);
            if (sessionId == null) {
                captureInitRequest(ctx, req, server);
                handlePostWithoutSession(ctx, req, origin);
                return;
            }
        }

        forwardToOperationHandler(ctx, req);
    }

    private void handlePostWithoutSession(ChannelHandlerContext ctx, FullHttpRequest req, @Nullable String origin) {
        // Read on the event loop, before the async hop: a pipelined next request must not be able
        // to overwrite the entry between the hop and the read.
        final PeekedBody.@Nullable Parsed peeked = PeekedBody.cached(ctx, req);
        var body = req.content().retain();

        CompletableFuture.runAsync(
                        () -> {
                            final JsonRpcMessage message;
                            try {
                                // A validation handler upstream already parsed this body; a null
                                // holder means none did.
                                message = peeked != null ? peeked.message() : dispatcher.parseMessage(body);
                            } finally {
                                body.release();
                            }
                            dispatchNoSessionMessage(ctx, message, origin);
                        },
                        executor)
                .exceptionally(ex -> {
                    logger.error("Failed to parse POST body during initialization", ex);
                    ctx.executor().execute(() -> {
                        var errorBytes = dispatcher.parseError(ChannelHandlerUtils.getInteractionContext(ctx));
                        sendResponseAndClose(
                                ctx, HttpResponseStatus.BAD_REQUEST, "application/json", errorBytes, origin);
                    });
                    return null;
                });
    }

    private void dispatchNoSessionMessage(
            ChannelHandlerContext ctx, @Nullable JsonRpcMessage message, @Nullable String origin) {
        switch (message) {
            case null ->
                ctx.executor()
                        .execute(() -> sendResponseAndClose(
                                ctx,
                                HttpResponseStatus.BAD_REQUEST,
                                "application/json",
                                dispatcher.parseError(ChannelHandlerUtils.getInteractionContext(ctx)),
                                origin));
            case JsonRpcMessage.Request<?> req
            when METHOD_INITIALIZE.equals(req.method()) -> handleInitialize(ctx, req.id(), req.params(), origin);
            default ->
                // Non-initialize request without session-id: dispatch via operation handler
                // (which will return "Missing MCP-Session-Id" or stateless ping etc.)
                dispatchPreSessionRequest(ctx, message, origin);
        }
    }

    private void dispatchPreSessionRequest(ChannelHandlerContext ctx, JsonRpcMessage message, @Nullable String origin) {
        if (!(message instanceof JsonRpcMessage.Request(RequestId id, String method, Object params))) {
            ctx.executor().execute(() -> {
                if (server.isStateless()) {
                    sendAccepted(ctx, origin);
                } else {
                    sendPlainTextAndClose(ctx, HttpResponseStatus.BAD_REQUEST, "Missing MCP-Session-Id header", origin);
                }
            });
            return;
        }
        var heartbeatInterval = server.config().network().heartbeatInterval();
        var postStream = new PostSseStream(ctx.channel(), origin, server::nextEventId, heartbeatInterval);
        final var transportCompletion = new CompletableFuture<Void>();
        dispatcher
                .dispatchRequestAsync(
                        id,
                        method,
                        params,
                        null,
                        postStream,
                        ChannelHandlerUtils.requireInteractionContext(ctx),
                        transportCompletion)
                .whenComplete((result, ex) -> marshalResponse(ctx, transportCompletion, () -> {
                    if (ex != null) {
                        postStream.terminate();
                        if (isRefused(ex)) {
                            logger.debug("Pre-session request refused: method={}", method);
                            completeOn(
                                    sendPlainTextAndClose(
                                            ctx,
                                            HttpResponseStatus.SERVICE_UNAVAILABLE,
                                            "Server shutting down",
                                            origin),
                                    transportCompletion);
                            return;
                        }
                        logger.error("Dispatch failed for pre-session request: method={}", method, ex);
                        completeOn(
                                sendResponseAndClose(
                                        ctx,
                                        HttpResponseStatus.INTERNAL_SERVER_ERROR,
                                        "application/json",
                                        dispatcher.parseError(ChannelHandlerUtils.requireInteractionContext(ctx)),
                                        origin),
                                transportCompletion);
                        return;
                    }
                    completeOn(completeDispatch(ctx, postStream, result, origin, null), transportCompletion);
                }));
    }

    /**
     * Terminates {@code postStream} and writes the response for a completed {@link
     * McpDispatcher.DispatchResult}, handling all three outcomes (accepted/status/response). Shared
     * by {@link #dispatchPreSessionRequest} and {@link #handleInitialize} — the only difference
     * between the two call sites is that {@code initialize} must fire the OPERATION-phase lifecycle
     * transition once the {@link McpDispatcher.DispatchResult.Response} is known, before the response
     * is written; {@code onResponseReady} carries that (a no-op for every other pre-session request).
     */
    private ChannelFuture completeDispatch(
            ChannelHandlerContext ctx,
            PostSseStream postStream,
            McpDispatcher.DispatchResult result,
            @Nullable String origin,
            @Nullable Consumer<McpDispatcher.DispatchResult.Response> onResponseReady) {
        if (result instanceof McpDispatcher.DispatchResult.Accepted) {
            postStream.terminate();
            return sendAcceptedAsync(ctx, origin);
        }
        if (result instanceof McpDispatcher.DispatchResult.Status(int code, String statusMessage)) {
            postStream.terminate();
            return sendPlainTextAndClose(ctx, HttpResponseStatus.valueOf(code), statusMessage, origin);
        }
        var response = (McpDispatcher.DispatchResult.Response) result;
        if (onResponseReady != null) {
            onResponseReady.accept(response);
        }
        if (postStream.started()) {
            postStream.writeEvent(server.nextEventId(), response.responseBody(), null);
            return postStream.terminateAsync();
        }
        postStream.terminate();
        return sendJsonResponse(
                ctx,
                response.responseBody(),
                HttpResponseStatus.valueOf(response.httpStatus()),
                response.sessionId(),
                origin);
    }

    private static void marshalResponse(
            ChannelHandlerContext ctx, CompletableFuture<Void> completion, Runnable response) {
        try {
            CompletableFuture.runAsync(response, ctx.executor()).whenComplete((unused, failure) -> {
                if (failure != null) completion.completeExceptionally(failure);
            });
        } catch (RuntimeException e) {
            completion.completeExceptionally(e);
        }
    }

    private void handleInitialize(ChannelHandlerContext ctx, RequestId id, Object params, @Nullable String origin) {
        var heartbeatInterval = server.config().network().heartbeatInterval();
        var postStream = new PostSseStream(ctx.channel(), origin, server::nextEventId, heartbeatInterval);
        final var startNs = System.nanoTime();
        logger.debug("Initialize request: id={}", id);

        final var transportCompletion = new CompletableFuture<Void>();
        dispatcher
                .dispatchRequestAsync(
                        id,
                        METHOD_INITIALIZE,
                        params,
                        null,
                        postStream,
                        ChannelHandlerUtils.requireInteractionContext(ctx),
                        transportCompletion)
                .whenComplete((result, ex) -> marshalResponse(ctx, transportCompletion, () -> {
                    var elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
                    if (ex != null) {
                        postStream.terminate();
                        if (isRefused(ex)) {
                            logger.debug("Initialize refused: id={}, elapsed={}ms", id, elapsedMs);
                            completeOn(
                                    sendPlainTextAndClose(
                                            ctx,
                                            HttpResponseStatus.SERVICE_UNAVAILABLE,
                                            "Server shutting down",
                                            origin),
                                    transportCompletion);
                            return;
                        }
                        logger.error("Initialize dispatch failed: id={}, elapsed={}ms", id, elapsedMs, ex);
                        completeOn(
                                sendResponseAndClose(
                                        ctx,
                                        HttpResponseStatus.INTERNAL_SERVER_ERROR,
                                        "application/json",
                                        dispatcher.parseError(ChannelHandlerUtils.requireInteractionContext(ctx)),
                                        origin),
                                transportCompletion);
                        return;
                    }
                    logger.debug("Initialize response: id={}, elapsed={}ms", id, elapsedMs);
                    completeOn(
                            completeDispatch(ctx, postStream, result, origin, response -> {
                                var resultSessionId = response.sessionId();
                                // Fire event with the live Session — InteractionHandler binds it into
                                // InteractionContext, and LifecyclePipelineCoordinator replaces this handler
                                // with McpOperationHandler.
                                var mcpSession = resultSessionId != null
                                        ? server.getLocalSession(resultSessionId)
                                                .orElse(null)
                                        : null;
                                ctx.pipeline()
                                        .fireUserEventTriggered(new InteractionEvent.OperationStarted(mcpSession));
                                logger.debug(
                                        "Pipeline transitioned to OPERATION phase for session: {}", resultSessionId);
                            }),
                            transportCompletion);
                }));
    }

    /**
     * Fires {@link InteractionEvent.OperationStarted#STATELESS} so the
     * {@link LifecyclePipelineCoordinator} replaces this handler with
     * {@link McpOperationHandler}, then forwards the request to it.
     * The operation handler's own try/finally releases the request;
     * our finally guard prevents a double-release via the {@code refCnt() > 0} check.
     */
    private void forwardToOperationHandler(ChannelHandlerContext ctx, FullHttpRequest req) {
        req.retain();
        try {
            ctx.pipeline().fireUserEventTriggered(InteractionEvent.OperationStarted.STATELESS);
            var opCtx = ctx.pipeline().context(McpHandlerManager.HANDLER_OPS);
            if (opCtx != null) {
                ctx.pipeline().get(McpOperationHandler.class).channelRead(opCtx, req);
            } else {
                logger.warn("Operation handler missing after phase transition; dropping request");
                req.release();
            }
        } catch (Exception e) {
            if (req.refCnt() > 0) {
                req.release();
            }
            throw e;
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent) {
            logger.debug(
                    "Idle timeout during initialization, closing channel: {}",
                    ctx.channel().remoteAddress());
            ctx.close();
        } else {
            ctx.fireUserEventTriggered(evt);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        // Abrupt close during init phase: fire ShutdownStarted so the coordinator
        // can remove the session (if one was created) and clean up extensions.
        var ic = ctx.channel().attr(InteractionHandler.INTERACTION_CONTEXT_KEY).get();
        if (ic != null && ic.session() != null) {
            ctx.pipeline()
                    .fireUserEventTriggered(
                            new InteractionEvent.ShutdownStarted(ic.session().id()));
        }
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (cause instanceof java.net.SocketException) {
            logger.debug("Connection reset during initialization", cause);
        } else {
            logger.error("MCP initialization error", cause);
        }
        ctx.close();
    }
}
