/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty.http;

import dev.tachyonmcp.api.annotations.InternalApi;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.cors.CorsConfig;
import io.netty.handler.codec.http.cors.CorsHandler;

/**
 * Netty's {@link CorsHandler} for preflights, without its response decoration.
 *
 * <p>{@code CorsHandler} keeps the request it read last in a per-channel field. Answering a preflight
 * reads that field in the same {@code channelRead} call, so preflights stay correct and are served by
 * Netty's logic unchanged. Decorating a response in {@code write()} reads the field later: on a
 * keep-alive connection that has meanwhile read another request, an asynchronous response would get
 * the other request's grant. So {@link #write} passes responses through untouched, and each response
 * carries the {@link CorsDecision} of its own request instead, from {@link #decide}.
 *
 * <p>One instance per channel, like {@code CorsHandler}.
 */
@InternalApi
public final class TachyonCorsHandler extends CorsHandler {

    private final CorsConfig config;

    /**
     * @param config the CORS configuration for preflights and response decisions
     */
    public TachyonCorsHandler(CorsConfig config) {
        super(config);
        this.config = config;
    }

    /**
     * Returns the CORS headers every response to {@code request} gets. A pipeline without this handler
     * — removed through a {@code pipelineCustomizer} — has opted out of Tachyon's CORS handling and
     * gets {@link CorsDecision#NONE}.
     *
     * @param ctx     any handler context of the request's channel
     * @param request the request being answered
     * @return the request's CORS decision
     */
    public static CorsDecision decide(ChannelHandlerContext ctx, HttpRequest request) {
        var handler = ctx.pipeline().get(TachyonCorsHandler.class);
        return handler == null ? CorsDecision.NONE : CorsDecision.of(handler.config, request);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        ctx.write(msg, promise);
    }
}
