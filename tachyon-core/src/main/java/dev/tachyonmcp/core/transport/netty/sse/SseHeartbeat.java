/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty.sse;

import dev.tachyonmcp.core.transport.netty.SessionTouchHandler;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.util.AttributeKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * Keeps long-lived SSE streams alive across idle periods. A channel carrying an open SSE response is
 * marked via {@link #enable(Channel, Duration)} which starts a scheduler that periodically writes
 * an SSE comment heartbeat ({@code :\r\n}). The scheduler runs on the channel's event loop.
 *
 * <p>A heartbeat byte flowing through the pipeline triggers {@link SessionTouchHandler}
 * which refreshes session liveness — no scattered {@code touch()} calls needed.
 *
 * <p>A heartbeat is itself a chunk write, so a failed write (dead client, RST) closes the channel.
 *
 * <p>Call with {@code interval <= 0} to disable heartbeats (silent SSE channels then close on idle
 * via the existing idle handler).
 */
public final class SseHeartbeat {

    private static final AttributeKey<@Nullable Boolean> ACTIVE = AttributeKey.valueOf("sseHeartbeatActive");
    private static final AttributeKey<@Nullable ScheduledFuture<?>> HEARTBEAT_FUTURE =
            AttributeKey.valueOf("sseHeartbeatFuture");

    // The payload is constant across every channel and every tick.
    private static final byte[] HEARTBEAT_BYTES = ":\r\n".getBytes(StandardCharsets.UTF_8);

    private SseHeartbeat() {}

    /**
     * Enables periodic heartbeats on {@code channel} at the given {@code interval}.
     * {@code interval <= 0} ({@link Duration#isZero()} or negative) disables heartbeats entirely.
     * Call after the SSE response headers are written.
     */
    public static void enable(Channel channel, Duration interval) {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            return;
        }
        channel.attr(ACTIVE).set(Boolean.TRUE);
        var millis = interval.toMillis();
        var future =
                channel.eventLoop().scheduleAtFixedRate(() -> send(channel), millis, millis, TimeUnit.MILLISECONDS);
        channel.attr(HEARTBEAT_FUTURE).set(future);
        channel.closeFuture().addListener(ignored -> {
            var f = channel.attr(HEARTBEAT_FUTURE).getAndSet(null);
            if (f != null) {
                f.cancel(false);
            }
        });
    }

    /**
     * Stops heartbeats on {@code channel}. Call on the channel's event loop before writing a
     * stream's terminating chunk: the scheduled tick is otherwise cancelled only once the channel
     * closes, so it could still emit a comment after that chunk, which the HTTP encoder no longer
     * accepts. Idempotent.
     */
    public static void cancel(Channel channel) {
        var future = channel.attr(HEARTBEAT_FUTURE).getAndSet(null);
        if (future != null) {
            future.cancel(false);
        }
        channel.attr(ACTIVE).set(null);
    }

    /** @return {@code true} if {@code channel} carries an open SSE stream. */
    public static boolean isEnabled(Channel channel) {
        return Boolean.TRUE.equals(channel.attr(ACTIVE).get());
    }

    /**
     * Writes an SSE comment heartbeat ({@code :\r\n}) on the channel, closing the channel
     * if the write fails. Skipped when the channel is inactive or already backpressured (bytes are
     * pending, so the stream is not actually idle).
     */
    public static void send(Channel channel) {
        if (!channel.isActive() || !channel.isWritable()) {
            return;
        }
        var buf = Unpooled.wrappedBuffer(HEARTBEAT_BYTES);
        channel.writeAndFlush(new DefaultHttpContent(buf)).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) channel.close();
        });
    }
}
