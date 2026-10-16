/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty.sse;

import io.netty.channel.Channel;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * Closes an SSE stream when the credentials of the request that opened it expire, so a stream cannot
 * outlive the token that authorized it. One timer per stream, on the channel's event loop: nothing is
 * checked per event, and the timer is dropped when the channel closes.
 *
 * <p>Every kind of stream passes through here when it starts, next to {@link SseHeartbeat}: the
 * 2025-11-25 {@code GET} stream, a streamed {@code POST} response and a 2026-07-28
 * {@code subscriptions/listen} stream. The opener passes the expiry of its own caller explicitly; this
 * reads no per-connection state. The stream is closed the way the server closes one that is done with
 * the client: a {@code retry} hint, the final chunk, then the connection. The client reconnects with fresh credentials, a 2025 client resuming from its
 * {@code Last-Event-ID}. This is a Tachyon decision: the MCP specification does not say what a stream
 * does when its token expires.
 *
 * <p>Never closes before the expiry. A caller with no expiry, anonymous or an API key, closes nothing.
 */
final class SseExpiry {

    private SseExpiry() {}

    /**
     * Arms the timer for the stream just started on {@code channel}.
     *
     * @param channel the stream's channel
     * @param clock the server's clock, the one that judges the token
     * @param expiresAt when the credentials of the request that opened the stream expire, or {@code null}
     *     for a caller whose credentials do not
     * @param closeStream closes the stream gracefully; runs on the channel's event loop
     */
    static void arm(Channel channel, Clock clock, @Nullable Instant expiresAt, Runnable closeStream) {
        if (expiresAt == null) {
            return;
        }
        final long delayNanos;
        try {
            delayNanos =
                    Math.max(0, Duration.between(clock.instant(), expiresAt).toNanos());
        } catch (ArithmeticException tooFarAhead) {
            return;
        }
        // The timer runs on the monotonic clock and the expiry is judged by the server's clock: when the
        // two disagree and it fires a little early, it waits out the rest instead of closing early.
        final Runnable atExpiry = () -> {
            if (clock.instant().isBefore(expiresAt)) {
                arm(channel, clock, expiresAt, closeStream);
            } else {
                closeStream.run();
            }
        };
        final var timer = channel.eventLoop().schedule(atExpiry, delayNanos, TimeUnit.NANOSECONDS);
        channel.closeFuture().addListener(closed -> timer.cancel(false));
    }
}
