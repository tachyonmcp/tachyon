/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty.sse;

import static dev.tachyonmcp.core.transport.netty.sse.SseManager.SSE_RETRY_DELAY_MS;

import dev.tachyonmcp.core.runtime.SseConnection;
import dev.tachyonmcp.core.runtime.SseEvent;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.LastHttpContent;
import java.util.Objects;

/**
 * {@link SseConnection} backed by a Netty {@link Channel}. Writes SSE frames
 * as chunked HTTP content and registers a close listener for cleanup.
 */
public final class NettySseConnection implements SseConnection {

    private final Channel channel;

    public NettySseConnection(Channel channel, final Runnable onCloseAction) {
        this.channel = Objects.requireNonNull(channel, "channel");
        channel.closeFuture().addListener(ignored -> onCloseAction.run());
    }

    public Channel channel() {
        return channel;
    }

    @Override
    public boolean isWritable() {
        return channel.isActive() && channel.isWritable();
    }

    @Override
    public void send(SseEvent event) {
        var eventLoop = channel.eventLoop();
        if (eventLoop.inEventLoop()) {
            doSend(event);
        } else {
            eventLoop.execute(() -> doSend(event));
        }
    }

    public void close() {
        var eventLoop = channel.eventLoop();
        if (eventLoop.inEventLoop()) {
            doClose();
        } else {
            eventLoop.execute(this::doClose);
        }
    }

    private void doSend(SseEvent event) {
        if (!channel.isActive()) return;
        var buf = SseSerializer.encode(channel.alloc(), event);
        channel.writeAndFlush(new DefaultHttpContent(buf)).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) channel.close();
        });
    }

    private void doClose() {
        if (channel.isActive()) {
            SseHeartbeat.cancel(channel);
            channel.write(new DefaultHttpContent(
                    ByteBufUtil.writeUtf8(channel.alloc(), "retry: " + SSE_RETRY_DELAY_MS + "\n")));
            channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).addListener(ChannelFutureListener.CLOSE);
        }
    }
}
