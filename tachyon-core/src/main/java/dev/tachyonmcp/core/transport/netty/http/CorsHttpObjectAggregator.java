/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty.http;

import dev.tachyonmcp.api.annotations.InternalApi;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpMessage;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import org.jspecify.annotations.Nullable;

/**
 * {@link HttpObjectAggregator} whose final rejection responses (413 and 417) carry the rejected
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
    protected @Nullable Object newContinueResponse(HttpMessage start, int maxContentLength, ChannelPipeline pipeline) {
        final var response = super.newContinueResponse(start, maxContentLength, pipeline);
        if (start instanceof HttpRequest request
                && response instanceof FullHttpResponse full
                && full.status().code() >= 400) {
            final var decorated = full.copy();
            full.release();
            TachyonCorsHandler.decide(pipeline.context(this), request).applyTo(decorated);
            return decorated;
        }
        return response;
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
