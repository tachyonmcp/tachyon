/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty.http;

import dev.tachyonmcp.api.annotations.InternalApi;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpMessage;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;

/**
 * {@link HttpObjectAggregator} whose {@code 413 Request Entity Too Large} carries the rejected
 * request's {@link CorsDecision}, so a browser page sees the status instead of an opaque CORS error.
 * Keeps Netty's close-or-keep-alive rule for the rejection unchanged.
 */
@InternalApi
public final class CorsHttpObjectAggregator extends HttpObjectAggregator {

    /**
     * @param maxContentLength the maximum aggregated body size in bytes
     */
    public CorsHttpObjectAggregator(int maxContentLength) {
        super(maxContentLength);
    }

    @Override
    protected void handleOversizedMessage(ChannelHandlerContext ctx, HttpMessage oversized) throws Exception {
        if (!(oversized instanceof HttpRequest request)) {
            super.handleOversizedMessage(ctx, oversized);
            return;
        }
        // Netty's rule: close once the body started arriving, when reads are manual, or when the client
        // neither waits for 100 Continue nor keeps the connection alive.
        var close = oversized instanceof FullHttpMessage
                || !ctx.channel().config().isAutoRead()
                || !HttpUtil.is100ContinueExpected(oversized) && !HttpUtil.isKeepAlive(oversized);
        var response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, Unpooled.EMPTY_BUFFER);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        if (close) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        }
        TachyonCorsHandler.decide(ctx, request).applyTo(response);
        var future = ctx.writeAndFlush(response);
        future.addListener(close ? ChannelFutureListener.CLOSE : ChannelFutureListener.CLOSE_ON_FAILURE);
    }
}
