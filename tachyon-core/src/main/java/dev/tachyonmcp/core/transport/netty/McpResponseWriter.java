/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.server.domain.RequestId;
import dev.tachyonmcp.core.protocol.ProtocolResponseMapper;
import dev.tachyonmcp.core.protocol.mcp.McpHeaderNames;
import dev.tachyonmcp.core.server.domain.ServerErrors;
import dev.tachyonmcp.core.transport.jsonrpc.JsonRpcCodec;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import org.jspecify.annotations.Nullable;

@InternalApi
public final class McpResponseWriter {

    private McpResponseWriter() {}

    /**
     * Answers a plain {@code OPTIONS} request with the methods the endpoint serves. CORS preflights
     * never get here: {@code CorsHandler} answers them, and adds CORS headers to every response.
     */
    public static void sendOptions(ChannelHandlerContext ctx) {
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NO_CONTENT);
        response.headers().set(HttpHeaderNames.ALLOW, "GET, POST, DELETE, OPTIONS");
        // Signal close so the client does not pool this socket; HttpServerKeepAliveHandler
        // appends `Connection: close` and closes the channel after this response.
        HttpUtil.setKeepAlive(response, false);
        ctx.writeAndFlush(response);
    }

    public static ChannelFuture sendJsonResponse(ChannelHandlerContext ctx, byte[] body, @Nullable String sessionId) {
        return sendJsonResponse(ctx, body, HttpResponseStatus.OK, false, sessionId);
    }

    public static ChannelFuture sendJsonResponse(
            ChannelHandlerContext ctx, byte[] body, HttpResponseStatus status, @Nullable String sessionId) {
        return sendJsonResponse(ctx, body, status, false, sessionId);
    }

    public static ChannelFuture sendJsonResponse(
            ChannelHandlerContext ctx,
            byte[] body,
            HttpResponseStatus status,
            boolean close,
            @Nullable String sessionId) {
        // Zero-copy wrap on the event loop: the byte[] is GC-managed until this point, so a
        // dropped task on the shutdown path is plain garbage, never a pooled-buffer leak.
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
        if (sessionId != null) {
            response.headers().set(McpHeaderNames.MCP_SESSION_ID, sessionId);
        }
        // HttpServerKeepAliveHandler appends `Connection: close` and closes the channel after
        // this response when keep-alive is disabled.
        HttpUtil.setKeepAlive(response, !close);
        return ctx.writeAndFlush(response);
    }

    /**
     * Writes a JSON-RPC internal-error response and closes the connection, for a dispatch failure
     * that occurred after a POST-SSE stream had already started (so the normal response path can no
     * longer send a result).
     *
     * @param ctx    the channel to write the response on
     * @param id     the id of the request that failed
     * @param mapper the protocol response mapper used to encode the error
     * @return the future for the write
     */
    public static ChannelFuture sendInternalError(
            ChannelHandlerContext ctx, RequestId id, ProtocolResponseMapper mapper) {
        var error = mapper.error(ServerErrors.internalError("Internal error"));
        var body = JsonRpcCodec.serializeError(id, error.code(), error.message(), error.data());
        return sendJsonResponse(ctx, body, HttpResponseStatus.valueOf(error.httpStatus()), true, null);
    }
}
