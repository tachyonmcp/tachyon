/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty;

import static dev.tachyonmcp.core.transport.netty.InteractionHandler.INTERACTION_CONTEXT_KEY;

import dev.tachyonmcp.api.server.domain.RequestId;
import dev.tachyonmcp.api.server.domain.ServerError;
import dev.tachyonmcp.api.server.session.SessionIdGenerator;
import dev.tachyonmcp.core.runtime.ChannelContext;
import dev.tachyonmcp.core.runtime.Session;
import dev.tachyonmcp.core.server.McpDispatcher;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.transport.jsonrpc.JsonRpcCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import org.jspecify.annotations.Nullable;

public final class ChannelHandlerUtils {

    private static final AttributeKey<Session> SESSION_KEY = AttributeKey.valueOf("tachyonSession");

    private ChannelHandlerUtils() {}

    private static final AttributeKey<Boolean> REJECTED = AttributeKey.valueOf("tachyonRequestRejected");

    /**
     * Rejects the request with {@code status} and closes the connection, marking the channel so
     * {@link #dropIfRejected} discards what the decoder already produced from the same read. Releases
     * {@code msg}: it is not forwarded on, so nothing else would free it.
     *
     * @param ctx     the channel handler context
     * @param msg     the inbound message being rejected
     * @param status  the HTTP status to respond with
     * @param message the plain-text response body; must not echo client-controlled input
     */
    public static void rejectAndClose(
            ChannelHandlerContext ctx, Object msg, HttpResponseStatus status, String message) {
        markRejected(ctx, msg);
        sendPlainTextAndClose(ctx, status, message);
    }

    /**
     * Rejects an aggregated MCP request with the negotiated protocol's wire form of {@code error} —
     * a JSON-RPC error body echoing {@code id}, under the HTTP status that protocol assigns the
     * error (2026-07-28 maps a header mismatch to {@code 400}, 2025-11-25 to {@code 200}, the
     * classic JSON-RPC-over-HTTP convention where the transport succeeded and the RPC did not).
     * Releases {@code req}: it is not forwarded on, so nothing else would free it.
     *
     * @param ctx   the channel handler context, carrying the negotiated interaction
     * @param req   the aggregated request being rejected
     * @param id    the JSON-RPC id to echo, or {@code null} for a notification
     * @param error the validation failure to report
     */
    public static void rejectWithServerError(
            ChannelHandlerContext ctx, FullHttpRequest req, @Nullable RequestId id, ServerError error) {
        var wireError =
                requireInteractionContext(ctx).protocol().responseMapper().error(error);
        var body = JsonRpcCodec.serializeError(id, wireError.code(), wireError.message(), wireError.data());
        var origin = req.headers().get(HttpHeaderNames.ORIGIN);
        markRejected(ctx, req);
        sendResponseAndClose(ctx, HttpResponseStatus.valueOf(wireError.httpStatus()), "application/json", body, origin);
    }

    /**
     * {@link #rejectAndClose} bookkeeping without the response, for handlers writing their own. Call
     * after reading anything still needed from {@code msg} — it releases the message.
     *
     * @param ctx the channel handler context
     * @param msg the inbound message being rejected
     */
    public static void markRejected(ChannelHandlerContext ctx, Object msg) {
        ctx.channel().attr(REJECTED).set(Boolean.TRUE);
        PeekedBody.clear(ctx);
        ReferenceCountUtil.release(msg);
    }

    /**
     * Releases {@code msg} and returns {@code true} when this channel already rejected a request via
     * {@link #rejectAndClose}. The decoder can emit the body's {@code HttpContent} chunks from the
     * same read batch as the {@link HttpRequest} that was rejected, and forwarding headerless content
     * downstream while the close is in flight leaks it into the next handler.
     *
     * <p>Called from exactly one place — {@code DnsRebindingProtectionHandler}, the first inbound
     * handler after the codec — so every later handler is covered no matter which one rejected.
     * Repeating the check in those handlers is unreachable: nothing forwards content past the head
     * once the flag is set. A pipeline customizer that removes {@code dns-rebinding} removes this
     * too.
     *
     * @param ctx the channel handler context
     * @param msg the inbound message
     * @return {@code true} if the message was dropped and the caller must return
     */
    public static boolean dropIfRejected(ChannelHandlerContext ctx, Object msg) {
        if (!Boolean.TRUE.equals(ctx.channel().attr(REJECTED).get())) {
            return false;
        }
        ReferenceCountUtil.release(msg);
        return true;
    }

    private static final AttributeKey<Throwable> CLOSE_FAILURE = AttributeKey.valueOf("closeFailure");

    /**
     * Stashes the cause of an abnormal channel close for {@link #closeFailure} to read back once
     * the channel's close future fires — Netty's {@code ChannelFuture} for that event carries no
     * cause of its own, so a genuine failure (an inbound {@code exceptionCaught}, a failed outbound
     * write) has to be recorded here before the channel is closed, distinguishing it from an
     * ordinary client-initiated disconnect.
     *
     * @param channel the channel about to be closed
     * @param cause   the failure that caused the close
     */
    public static void markCloseFailure(Channel channel, Throwable cause) {
        channel.attr(CLOSE_FAILURE).set(cause);
    }

    /**
     * Returns the cause stashed by {@link #markCloseFailure}, or {@code null} for an ordinary close.
     *
     * @param channel the channel that closed
     * @return the failure cause, or {@code null}
     */
    public static @Nullable Throwable closeFailure(Channel channel) {
        return channel.attr(CLOSE_FAILURE).get();
    }

    /**
     * Binds a session to the channel and installs the {@link SessionTouchHandler} if not already
     * present. Every outbound byte written to this channel will refresh the session's liveness.
     *
     * @param ctx     the channel handler context
     * @param session the session to bind
     */
    public static void setSession(ChannelHandlerContext ctx, Session session) {
        SessionTouchHandler.install(ctx);
        bindSession(ctx.channel(), session);
    }

    /** Binds a session after the pipeline's session touch handler has been installed. */
    public static void bindSession(Channel channel, Session session) {
        channel.attr(SESSION_KEY).set(session);
    }

    /**
     * Returns the session bound to this channel, or {@code null}.
     *
     * @param channel the channel
     * @return the session, or {@code null}
     */
    public static @Nullable Session getSession(Channel channel) {
        return channel.attr(SESSION_KEY).get();
    }

    /**
     * Returns the protocol interaction context bound to this channel, or {@code null}.
     *
     * @param ctx the channel handler context
     * @return the interaction context, or {@code null}
     */
    public static @Nullable ChannelContext getInteractionContext(ChannelHandlerContext ctx) {
        return ctx.channel().attr(INTERACTION_CONTEXT_KEY).get();
    }

    public static ChannelContext requireInteractionContext(ChannelHandlerContext ctx) {
        return Objects.requireNonNull(
                getInteractionContext(ctx),
                "InteractionContext is null. Check if InteractionHandler is configured correctly.");
    }

    /**
     * When a custom {@link SessionIdGenerator} is configured on a stateful server, stashes a
     * detached copy of the request (method/URI/headers) on the channel's interaction context so
     * the generator can read it at session-creation time. A copy is required because the pooled
     * request is released before the async dispatch runs.
     *
     * @param ctx    the channel handler context
     * @param req    the HTTP request to capture
     * @param server the server engine
     */
    public static void captureInitRequest(ChannelHandlerContext ctx, HttpRequest req, ServerEngine server) {
        if (server.isStateless() || !server.sessionIdGenerator().readsRequest()) {
            return;
        }
        // Always build a fresh metadata-only snapshot. The aggregated request is a pooled
        // DefaultFullHttpRequest (a DefaultHttpRequest subclass): stashing it — or its headers
        // collection — aliases state that is released and recycled before the async dispatch reads
        // it. Copy the request line and headers into a detached request; the body is intentionally
        // dropped (a session-id generator reads headers/URI, and the pooled body is already gone).
        var headers = new DefaultHttpHeaders().set(req.headers());
        var snapshot = new DefaultHttpRequest(req.protocolVersion(), req.method(), req.uri(), headers);
        requireInteractionContext(ctx).set(McpDispatcher.ATTR_INIT_REQUEST, snapshot);
    }

    public static void sendAccepted(ChannelHandlerContext ctx, @Nullable String origin) {
        sendAcceptedAsync(ctx, origin);
    }

    /**
     * Returns whether a failed dispatch was refused because the server has no capacity for it —
     * shutdown has started, or the handler executor rejected the task. Callers answer these with
     * {@code 503 Service Unavailable} and a DEBUG log, not a {@code 500} and an ERROR: nothing is
     * broken and no operator action is needed.
     */
    public static boolean isRefused(Throwable ex) {
        return ex instanceof CompletionException ce && ce.getCause() != null
                ? ce.getCause() instanceof RejectedExecutionException
                : ex instanceof RejectedExecutionException;
    }

    /** Writes an accepted response and returns its write completion. */
    public static ChannelFuture sendAcceptedAsync(ChannelHandlerContext ctx, @Nullable String origin) {
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.ACCEPTED);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        if (origin != null) {
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
        }
        return ctx.writeAndFlush(response);
    }

    /**
     * Completes {@code transportCompletion} once {@code future} finishes, mirroring its outcome —
     * used to let {@link dev.tachyonmcp.core.server.internal.OperationTracker#drain} know a
     * response write has finished, whether it succeeded or failed.
     */
    public static void completeOn(ChannelFuture future, CompletableFuture<Void> transportCompletion) {
        future.addListener(f -> {
            if (f.isSuccess()) {
                transportCompletion.complete(null);
            } else {
                transportCompletion.completeExceptionally(f.cause());
            }
        });
    }

    /**
     * Writes a {@code text/plain} response and closes the connection. The response carries
     * {@code Connection: close} so the client does not return the socket to its keep-alive pool
     * (reusing a socket the server is about to close causes intermittent "other side closed"
     * errors, e.g. with undici on Linux). Prefer this over {@code sendPlainText(...).addListener(CLOSE)}.
     */
    public static ChannelFuture sendPlainTextAndClose(
            ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        return sendPlainTextAndClose(ctx, status, message, null);
    }

    /**
     * {@link #sendPlainTextAndClose(ChannelHandlerContext, HttpResponseStatus, String)} echoing {@code origin}.
     */
    public static ChannelFuture sendPlainTextAndClose(
            ChannelHandlerContext ctx, HttpResponseStatus status, String message, @Nullable String origin) {
        return sendResponse(ctx, status, "text/plain", ByteBufUtil.writeUtf8(ctx.alloc(), message), true, origin);
    }

    /**
     * Writes a response with the given content type and body, then closes the connection with a
     * {@code Connection: close} header. See {@link #sendPlainTextAndClose} for why the header matters.
     */
    public static ChannelFuture sendResponseAndClose(
            ChannelHandlerContext ctx,
            HttpResponseStatus status,
            String contentType,
            ByteBuf body,
            @Nullable String origin) {
        return sendResponse(ctx, status, contentType, body, true, origin);
    }

    /** Zero-copy overload for GC-managed bodies: wraps the byte[] at send time on the event loop. */
    public static ChannelFuture sendResponseAndClose(
            ChannelHandlerContext ctx,
            HttpResponseStatus status,
            String contentType,
            byte[] body,
            @Nullable String origin) {
        return sendResponse(ctx, status, contentType, Unpooled.wrappedBuffer(body), true, origin);
    }

    private static ChannelFuture sendResponse(
            ChannelHandlerContext ctx,
            HttpResponseStatus status,
            String contentType,
            ByteBuf body,
            boolean close,
            @Nullable String origin) {
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, body);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.readableBytes());
        if (origin != null) {
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
        }
        // Mark the keep-alive intent; HttpServerKeepAliveHandler adds `Connection: close`
        // and closes the channel after this response when keep-alive is disabled.
        HttpUtil.setKeepAlive(response, !close);
        return ctx.writeAndFlush(response);
    }
}
