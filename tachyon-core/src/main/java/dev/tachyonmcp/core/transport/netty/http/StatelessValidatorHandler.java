/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty.http;

import static dev.tachyonmcp.core.transport.netty.ChannelHandlerUtils.rejectAndClose;

import dev.tachyonmcp.api.annotations.InternalApi;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * Guards stateless-mode channels: rejects requests carrying session-related
 * headers ({@code MCP-Session-Id}, {@code Last-Event-ID}) and DELETE methods.
 */
@ChannelHandler.Sharable
@InternalApi
public class StatelessValidatorHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof HttpRequest req) {
            var sessionId = req.headers().get("MCP-Session-Id");
            var lastEventId = req.headers().get("Last-Event-ID");
            if (sessionId != null || lastEventId != null) {
                rejectAndClose(
                        ctx,
                        msg,
                        HttpResponseStatus.NOT_FOUND,
                        "Stateless server does not support sessions",
                        TachyonCorsHandler.decide(ctx, req));
                return;
            }

            if (req.method() == HttpMethod.DELETE) {
                rejectAndClose(
                        ctx,
                        msg,
                        HttpResponseStatus.METHOD_NOT_ALLOWED,
                        "Session management not available in stateless mode",
                        TachyonCorsHandler.decide(ctx, req));
                return;
            }
        }
        ctx.fireChannelRead(msg);
    }
}
