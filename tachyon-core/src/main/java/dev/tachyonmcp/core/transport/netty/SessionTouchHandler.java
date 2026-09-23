/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.core.runtime.Session;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;

/**
 * Outbound handler that refreshes session liveness on every byte written to the channel.
 * Any outbound HTTP writing — SSE frames, heartbeat comments, notifications, responses —
 * traverses this handler and calls {@link Session#touch()}, keeping the
 * session alive without scattered {@code touch()} calls across the codebase.
 *
 * <p>Shared across all pipelines ({@code @Sharable}, stateless). Install via
 * {@link #install(ChannelHandlerContext)} at the point where a session is bound to the channel.
 */
@ChannelHandler.Sharable
@InternalApi
public final class SessionTouchHandler extends ChannelOutboundHandlerAdapter {

    private static final String HANDLER_NAME = "session-touch";
    private static final SessionTouchHandler INSTANCE = new SessionTouchHandler();

    /**
     * Installs the shared {@link SessionTouchHandler} into the channel pipeline, if not already
     * present. Safe to call multiple times per channel. In production the pipeline always has an
     * {@code "http"} (HttpServerCodec) handler, so the handler is added after it. In test pipelines
     * without one, the handler is added immediately before the binding handler.
     */
    public static void install(ChannelHandlerContext ctx) {
        var pipeline = ctx.pipeline();
        if (pipeline.get(HANDLER_NAME) == null) {
            if (pipeline.get("http") != null) {
                pipeline.addAfter("http", HANDLER_NAME, INSTANCE);
            } else {
                pipeline.addBefore(ctx.name(), HANDLER_NAME, INSTANCE);
            }
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        var session = ChannelHandlerUtils.getSession(ctx.channel());
        if (session != null) {
            session.touch();
        }
        ctx.write(msg, promise);
    }
}
