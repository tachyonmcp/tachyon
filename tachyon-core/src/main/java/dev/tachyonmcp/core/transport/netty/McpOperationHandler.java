/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty;

import static dev.tachyonmcp.core.transport.netty.ChannelHandlerUtils.bindSession;
import static dev.tachyonmcp.core.transport.netty.ChannelHandlerUtils.captureInitRequest;
import static dev.tachyonmcp.core.transport.netty.ChannelHandlerUtils.completeOn;
import static dev.tachyonmcp.core.transport.netty.ChannelHandlerUtils.isRefused;
import static dev.tachyonmcp.core.transport.netty.ChannelHandlerUtils.sendAccepted;
import static dev.tachyonmcp.core.transport.netty.ChannelHandlerUtils.sendAcceptedAsync;
import static dev.tachyonmcp.core.transport.netty.ChannelHandlerUtils.sendPlainTextAndClose;
import static dev.tachyonmcp.core.transport.netty.ChannelHandlerUtils.sendResponseAndClose;
import static dev.tachyonmcp.core.transport.netty.McpResponseWriter.sendInternalError;
import static dev.tachyonmcp.core.transport.netty.McpResponseWriter.sendJsonResponse;
import static dev.tachyonmcp.core.transport.netty.McpResponseWriter.sendOptions;

import dev.tachyonmcp.api.server.domain.RequestId;
import dev.tachyonmcp.core.protocol.mcp.McpHeaderNames;
import dev.tachyonmcp.core.runtime.ChannelContext;
import dev.tachyonmcp.core.runtime.Session;
import dev.tachyonmcp.core.runtime.SseEvent;
import dev.tachyonmcp.core.server.McpDispatcher;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.server.session.SessionEvent;
import dev.tachyonmcp.core.transport.jsonrpc.JsonRpcCodec;
import dev.tachyonmcp.core.transport.jsonrpc.JsonRpcMessage;
import dev.tachyonmcp.core.transport.netty.sse.PostSseStream;
import dev.tachyonmcp.core.transport.netty.sse.SseHeartbeat;
import dev.tachyonmcp.core.transport.netty.sse.SseManager;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.timeout.IdleStateEvent;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles MCP requests during the OPERATION lifecycle phase: all JSON-RPC methods
 * after the initial {@code initialize} handshake. Added to the pipeline either
 * directly (stateless mode) or dynamically by {@link McpInitializationHandler}
 * after a successful initialize.
 *
 * <p>Instantiated per channel (see {@link McpHandlerManager#createOperationHandler()});
 * per-connection state lives in {@link InteractionHandler}'s channel attribute.
 */
public class McpOperationHandler extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(McpOperationHandler.class);

    private final ServerEngine server;
    private final McpDispatcher dispatcher;
    private final Executor executor;
    private final SseManager sseManager;

    public McpOperationHandler(ServerEngine server, McpDispatcher dispatcher, Executor executor) {
        this.server = server;
        this.dispatcher = dispatcher;
        this.executor = executor;
        this.sseManager = new SseManager(server);
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        SessionTouchHandler.install(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof FullHttpRequest req) {
            try {
                handleRequest(ctx, req);
            } finally {
                if (req.refCnt() > 0) {
                    req.release();
                }
            }
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    void handleRequest(ChannelHandlerContext ctx, FullHttpRequest req) {
        logger.debug("MCP request: {} {}", req.method(), req.uri());

        HttpMethod method = req.method();
        if (method == HttpMethod.OPTIONS) {
            sendOptions(ctx);
        } else if (method == HttpMethod.POST) {
            handlePost(ctx, req);
        } else if (method == HttpMethod.GET) {
            handleGet(ctx, req);
        } else if (method == HttpMethod.DELETE) {
            handleDelete(ctx, req);
        } else {
            sendResponseAndClose(
                    ctx,
                    HttpResponseStatus.METHOD_NOT_ALLOWED,
                    "text/plain",
                    ctx.alloc().buffer(0));
        }
    }

    private void handlePost(ChannelHandlerContext ctx, FullHttpRequest req) {
        var sessionId = req.headers().get(McpHeaderNames.MCP_SESSION_ID);
        if (sessionId == null) {
            // A session-less POST may be an initialize (e.g. on a keep-alive channel already in
            // the operation phase); preserve the request for a custom SessionIdGenerator.
            captureInitRequest(ctx, req, server);
        }
        // Captured synchronously, before the async hop below: a pipelined next request's
        // channelRead cannot run until this method returns, so this pins the interaction
        // context this request actually arrived on instead of racing a later re-fetch
        // against ProtocolVersionHandler rebinding a fresh one for the next request. May be
        // null (e.g. no InteractionHandler configured); enforced non-null only where the
        // original code enforced it, in handlePostRequest.
        final @Nullable ChannelContext ic = ChannelHandlerUtils.getInteractionContext(ctx);
        // Read for the same reason and in the same place: the entry belongs to this request, and a
        // pipelined next request must not be able to overwrite it between the hop and the read.
        final PeekedBody.@Nullable Parsed peeked = PeekedBody.cached(ctx, req);
        var body = req.content().retain();
        try {
            CompletableFuture.runAsync(() -> parseAndDispatchPost(ctx, sessionId, body, ic, peeked), executor)
                    .exceptionally(ex -> {
                        logger.debug("Failed to parse POST body", ex);
                        ctx.executor()
                                .execute(() -> sendResponseAndClose(
                                        ctx,
                                        HttpResponseStatus.BAD_REQUEST,
                                        "application/json",
                                        dispatcher.parseError(ic)));
                        return null;
                    });
        } catch (RejectedExecutionException e) {
            body.release();
            sendPlainTextAndClose(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "Server shutting down");
        }
    }

    /**
     * Resolves and binds the session, parses the body, then hands the message on. Always releases
     * {@code body}. Returns without dispatching if the session is unknown or cannot be resolved,
     * having already written the error response.
     */
    private void parseAndDispatchPost(
            ChannelHandlerContext ctx,
            @Nullable String sessionId,
            ByteBuf body,
            @Nullable ChannelContext ic,
            PeekedBody.@Nullable Parsed peeked) {
        final JsonRpcCodec.Parse parse;
        try {
            if (sessionId != null) {
                final Optional<Session> session;
                try {
                    session = server.getSession(sessionId);
                } catch (RuntimeException e) {
                    logger.warn("Failed to resolve session: {}", sessionId, e);
                    ctx.executor()
                            .execute(() -> sendPlainTextAndClose(
                                    ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "Session lookup failed"));
                    return;
                }
                if (session.isEmpty()) {
                    ctx.executor()
                            .execute(() -> sendPlainTextAndClose(ctx, HttpResponseStatus.NOT_FOUND, "Unknown session"));
                    return;
                }
                bindSession(ctx.channel(), session.orElseThrow());
            }
            // A validation handler upstream already parsed this body; a null holder means none did.
            parse = peeked != null ? peeked.parse() : dispatcher.parseBody(body);
        } finally {
            body.release();
        }
        dispatchPostMessage(ctx, sessionId, parse, ic);
    }

    private void dispatchPostMessage(
            ChannelHandlerContext ctx,
            @Nullable String sessionId,
            JsonRpcCodec.Parse parse,
            @Nullable ChannelContext ic) {
        // Named to not shadow the worker `executor` field: every write below must run on the
        // channel's event loop, while `executor` off-loads work away from it.
        final var eventLoop = ctx.executor();
        var message = parse.message();
        if (message == null) {
            eventLoop.execute(() -> sendResponseAndClose(
                    ctx,
                    HttpResponseStatus.BAD_REQUEST,
                    "application/json",
                    dispatcher.malformedBodyError(parse.invalidRequest(), ic)));
            return;
        }
        if (!server.isStateless() && sessionId == null && !(message instanceof JsonRpcMessage.Request<?>)) {
            eventLoop.execute(
                    () -> sendPlainTextAndClose(ctx, HttpResponseStatus.BAD_REQUEST, "Missing MCP-Session-Id header"));
            return;
        }
        switch (message) {
            case JsonRpcMessage.Request<?> reqMsg ->
                handlePostRequest(
                        ctx,
                        sessionId,
                        reqMsg,
                        Objects.requireNonNull(
                                ic,
                                "InteractionContext is null. Check if InteractionHandler is configured correctly."));
            case JsonRpcMessage.Response resp ->
                handlePostResponse(ctx, sessionId, ctx.channel().id().asLongText(), resp);
            case JsonRpcMessage.Error err ->
                handlePostError(ctx, sessionId, ctx.channel().id().asLongText(), err);
            case JsonRpcMessage.Notification<?> not -> {
                // Apply the notification before acking so a client that waits for this 202 observes
                // its effect on the next request it sends: an ACTIVE session for `initialized`, and
                // for `cancelled` a cancellation that can no longer land on a later request reusing
                // the same JSON-RPC id. Already on the worker executor, so this blocks no event
                // loop. Guarded so a handler failure still produces the ack.
                try {
                    dispatcher.dispatchNotification(not.method(), not.params(), sessionId, ic);
                } catch (RuntimeException e) {
                    logger.warn("Failed to process {} notification", not.method(), e);
                }
                eventLoop.execute(() -> sendAccepted(ctx));
            }
            default -> {
                logger.warn("Unexpected message type: {}", message);
                eventLoop.execute(() -> sendAccepted(ctx));
            }
        }
    }

    private void handlePostResponse(
            ChannelHandlerContext ctx, @Nullable String sessionId, String channelId, JsonRpcMessage.Response resp) {
        ctx.executor().execute(() -> sendAccepted(ctx));
        executor.execute(() -> {
            if (!server.completePendingRequest(resp.id(), sessionId, channelId, resp.resultJson())) {
                logger.debug("No matching pending request for response id: {}", resp.id());
            }
        });
    }

    private void handlePostError(
            ChannelHandlerContext ctx, @Nullable String sessionId, String channelId, JsonRpcMessage.Error err) {
        ctx.executor().execute(() -> sendAccepted(ctx));
        executor.execute(() -> {
            if (!server.failPendingRequest(err.id(), sessionId, channelId, err.code() + ": " + err.message())) {
                logger.debug("No matching pending request for error id: {}", err.id());
            }
        });
    }

    private void handlePostRequest(
            ChannelHandlerContext ctx, @Nullable String sessionId, JsonRpcMessage.Request req, ChannelContext ic) {
        var heartbeatInterval = server.config().network().heartbeatInterval();
        var postStream = new PostSseStream(ctx.channel(), server::nextEventId, heartbeatInterval);
        final var requestId = req.id();
        final var method = req.method();
        final var startNs = System.nanoTime();
        final var transportCompletion = new CompletableFuture<Void>();
        dispatcher
                .dispatchRequestAsync(requestId, method, req.params(), sessionId, postStream, ic, transportCompletion)
                .whenComplete((result, ex) -> {
                    try {
                        ctx.executor()
                                .execute(() -> completePostRequest(
                                        ctx,
                                        requestId,
                                        method,
                                        sessionId,
                                        postStream,
                                        startNs,
                                        result,
                                        ex,
                                        ic,
                                        transportCompletion));
                    } catch (RejectedExecutionException e) {
                        transportCompletion.completeExceptionally(e);
                        logger.debug(
                                "Event loop rejected response marshal during shutdown: id={}, method={}",
                                requestId,
                                method);
                    }
                });
    }

    private void completePostRequest(
            ChannelHandlerContext ctx,
            RequestId requestId,
            String method,
            @Nullable String sessionId,
            PostSseStream postStream,
            long startNs,
            McpDispatcher.@Nullable DispatchResult result,
            @Nullable Throwable ex,
            ChannelContext ic,
            CompletableFuture<Void> transportCompletion) {
        try {
            var elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
            var m = server.config().observability();
            if (ex != null) {
                final var refused = isRefused(ex);
                if (refused) {
                    logger.debug("Dispatch refused: id={}, method={}, elapsed={}ms", requestId, method, elapsedMs);
                } else {
                    logger.error("Dispatch failed: id={}, method={}, elapsed={}ms", requestId, method, elapsedMs, ex);
                }
                if (postStream.started()) {
                    completeOn(postStream.terminateAsync(), transportCompletion);
                } else {
                    // Neutralize the stream so a late server→client message cannot start a
                    // second HTTP response on this channel, then send the error.
                    postStream.terminate();
                    completeOn(
                            refused
                                    ? sendPlainTextAndClose(
                                            ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "Server shutting down")
                                    : sendInternalError(
                                            ctx, requestId, ic.protocol().responseMapper()),
                            transportCompletion);
                }
                return;
            }
            if (result instanceof McpDispatcher.DispatchResult.Status(int code, String message)) {
                // Transport-level signal from the dispatcher — spec ties this condition to a raw HTTP
                // status, not a JSON-RPC error envelope.
                postStream.terminate();
                completeOn(sendPlainTextAndClose(ctx, HttpResponseStatus.valueOf(code), message), transportCompletion);
                return;
            }
            if (postStream.started()) {
                if (m.slowRequestLogging()
                        && elapsedMs > m.slowRequestThreshold().toMillis()) {
                    logger.warn("Slow POST-SSE response: id={}, method={}, elapsed={}ms", requestId, method, elapsedMs);
                } else {
                    logger.debug("POST-SSE response: id={}, method={}, elapsed={}ms", requestId, method, elapsedMs);
                }
                // Finalization decodes the response body and appends to the event log —
                // too heavy for the event loop. PostSseStream writes marshal themselves.
                try {
                    executor.execute(() ->
                            finalizePostSseResponse(requestId, sessionId, postStream, result, transportCompletion));
                } catch (RejectedExecutionException e) {
                    completeOn(postStream.terminateAsync(), transportCompletion);
                }
                return;
            }
            // A keep-alive JSON/202 response is about to be written; neutralize the unused
            // stream so a server→client message that arrives after this check (e.g. an async
            // tool's status notification) cannot open a second response on the pooled socket
            // and corrupt the next request's reuse of it.
            postStream.terminate();
            if (result instanceof McpDispatcher.DispatchResult.Accepted) {
                completeOn(sendAcceptedAsync(ctx), transportCompletion);
                return;
            }
            if (m.slowRequestLogging() && elapsedMs > m.slowRequestThreshold().toMillis()) {
                logger.warn("Slow POST response: id={}, method={}, elapsed={}ms", requestId, method, elapsedMs);
            } else {
                logger.debug("POST response: id={}, method={}, elapsed={}ms", requestId, method, elapsedMs);
            }
            var response = (McpDispatcher.DispatchResult.Response) result;
            completeOn(
                    sendJsonResponse(
                            ctx,
                            response.responseBody(),
                            HttpResponseStatus.valueOf(response.httpStatus()),
                            response.sessionId()),
                    transportCompletion);
        } catch (RuntimeException e) {
            transportCompletion.completeExceptionally(e);
            throw e;
        }
    }

    private void finalizePostSseResponse(
            RequestId requestId,
            @Nullable String sessionId,
            PostSseStream postStream,
            McpDispatcher.@Nullable DispatchResult result,
            CompletableFuture<Void> transportCompletion) {
        if (!(result instanceof McpDispatcher.DispatchResult.Response response)) {
            completeOn(postStream.terminateAsync(), transportCompletion);
            return;
        }
        var responseBody = response.responseBody();
        try {
            var sseEventId = server.nextEventId();
            Runnable onDropped = null;
            if (!server.isStateless()) {
                var resultJson = new String(responseBody, StandardCharsets.UTF_8);
                server.appendEvent(new SessionEvent.ResponseEvent(
                        sessionId,
                        requestId,
                        resultJson,
                        System.currentTimeMillis(),
                        sseEventId,
                        postStream.streamKey()));
                onDropped = redeliverOnReconnect(sessionId, postStream.streamKey(), sseEventId, resultJson);
            }
            postStream.writeEvent(sseEventId, responseBody, onDropped);
        } catch (RuntimeException e) {
            logger.error("Failed to write final response on POST-SSE stream", e);
        } finally {
            completeOn(postStream.terminateAsync(), transportCompletion);
        }
    }

    /**
     * Builds the fallback that runs when the final response could not be written because the tool
     * already closed its POST-SSE stream: if the client has reconnected and explicitly resumed this
     * stream key, deliver the response live on that stream. Otherwise it stays in the event log for
     * {@code Last-Event-ID} replay. Never crosses to a different stream (MCP resumability rule).
     */
    private @Nullable Runnable redeliverOnReconnect(
            @Nullable String sessionId, String streamKey, long sseEventId, String resultJson) {
        if (sessionId == null) {
            return null;
        }
        var wireId = ServerEngine.wireEventId(sseEventId, streamKey);
        return () -> server.getLocalSession(sessionId).ifPresent(session -> {
            // ponytail: the resumed reconnect may also replay this event from the log, so a rare
            // race can deliver it twice with the same SSE id — harmless (the client dedupes the
            // JSON-RPC response by request id). Per-connection id de-dup if that ever bites.
            if (streamKey.equals(session.resumingStreamKey())) {
                session.send(new SseEvent(wireId, "message", resultJson));
            }
        });
    }

    private void handleGet(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (server.isStateless()) {
            sseManager.openStatelessStream(ctx);
            return;
        }
        var sessionId = req.headers().get(McpHeaderNames.MCP_SESSION_ID);
        if (sessionId == null || sessionId.isEmpty()) {
            sendPlainTextAndClose(ctx, HttpResponseStatus.BAD_REQUEST, "Missing MCP-Session-Id header");
            return;
        }
        final var lastEventId = req.headers().get(McpHeaderNames.LAST_EVENT_ID);
        final var local = server.getLocalSession(sessionId);
        if (local.isPresent()) {
            sseManager.openStream(ctx, local.orElseThrow(), lastEventId);
            return;
        }
        try {
            CompletableFuture.supplyAsync(() -> server.getSession(sessionId), executor)
                    .whenComplete((session, failure) -> marshalSessionLookup(
                            ctx, sessionId, session, failure, found -> sseManager.openStream(ctx, found, lastEventId)));
        } catch (RejectedExecutionException e) {
            sendPlainTextAndClose(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "Server shutting down");
        }
    }

    private void handleDelete(ChannelHandlerContext ctx, FullHttpRequest req) {
        var sessionId = req.headers().get(McpHeaderNames.MCP_SESSION_ID);
        if (sessionId == null || sessionId.isEmpty()) {
            sendPlainTextAndClose(ctx, HttpResponseStatus.BAD_REQUEST, "Missing MCP-Session-Id header");
            return;
        }
        try {
            CompletableFuture.supplyAsync(
                            () -> {
                                if (server.getSession(sessionId).isEmpty()) {
                                    return false;
                                }
                                server.removeSession(sessionId);
                                return true;
                            },
                            executor)
                    .whenComplete((removed, failure) -> ctx.executor().execute(() -> {
                        if (failure != null) {
                            logger.error("Failed to terminate session: {}", sessionId, failure);
                            sendPlainTextAndClose(
                                    ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "Session termination failed");
                        } else if (!removed) {
                            sendPlainTextAndClose(ctx, HttpResponseStatus.NOT_FOUND, "Unknown session");
                        } else {
                            sendPlainTextAndClose(ctx, HttpResponseStatus.OK, "");
                            logger.info("Session terminated via DELETE: {}", sessionId);
                        }
                    }));
        } catch (RejectedExecutionException e) {
            sendPlainTextAndClose(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "Server shutting down");
        }
    }

    private static void marshalSessionLookup(
            ChannelHandlerContext ctx,
            String sessionId,
            @Nullable Optional<Session> session,
            @Nullable Throwable failure,
            Consumer<Session> onFound) {
        try {
            ctx.executor().execute(() -> {
                if (failure != null) {
                    logger.error("Failed to resolve session: {}", sessionId, failure);
                    sendPlainTextAndClose(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "Session lookup failed");
                } else if (session == null || session.isEmpty()) {
                    sendPlainTextAndClose(ctx, HttpResponseStatus.NOT_FOUND, "Unknown session");
                } else {
                    onFound.accept(session.orElseThrow());
                }
            });
        } catch (RejectedExecutionException e) {
            logger.debug("Event loop rejected session lookup result during shutdown: {}", sessionId);
        }
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        var writable = ctx.channel().isWritable();
        ctx.channel().config().setAutoRead(writable);
        ctx.fireChannelWritabilityChanged();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        ctx.fireChannelInactive();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent idle) {
            if (!SseHeartbeat.ignoresIdle(ctx.channel(), idle)) {
                logger.debug("Idle timeout, closing channel: {}", ctx.channel().remoteAddress());
                ctx.close();
            }
        } else {
            ctx.fireUserEventTriggered(evt);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (cause instanceof java.net.SocketException) {
            logger.debug("Connection reset on MCP endpoint", cause);
        } else {
            logger.error("MCP endpoint error", cause);
            ChannelHandlerUtils.markCloseFailure(ctx.channel(), cause);
        }
        ctx.close();
    }
}
