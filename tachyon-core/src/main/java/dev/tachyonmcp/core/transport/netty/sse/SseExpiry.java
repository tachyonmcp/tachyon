/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty.sse;

import io.netty.channel.Channel;
import io.netty.util.concurrent.ScheduledFuture;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * Closes an SSE stream when the credentials of the request that opened it expire, so a stream cannot
 * outlive the token that authorized it. One timer per stream, on the channel's event loop: nothing is
 * checked per event, and the stream {@linkplain #cancel cancels} the timer when it ends, so a
 * keep-alive connection that serves many streamed responses holds no timer for a stream that is over.
 *
 * <p>Every kind of stream passes through here when it starts, next to {@link SseHeartbeat}: the
 * 2025-11-25 {@code GET} stream, a streamed {@code POST} response and a 2026-07-28
 * {@code subscriptions/listen} stream. The opener passes the expiry of its own caller explicitly; this
 * reads no per-connection state. The stream is closed the way the server closes one that is done with
 * the client: a {@code retry} hint, the final chunk, then the connection. The client reconnects with
 * fresh credentials, a 2025 client resuming from its {@code Last-Event-ID}. This is a Tachyon
 * decision: the MCP specification does not say what a stream does when its token expires.
 *
 * <p>Never closes before the expiry. A caller with no expiry, anonymous or an API key, closes nothing.
 * Event-loop confined, like the stream that owns it.
 */
final class SseExpiry {

    private final Channel channel;
    private final Clock clock;
    private final Instant expiresAt;
    private final Runnable closeStream;
    private @Nullable ScheduledFuture<?> timer;

    private SseExpiry(Channel channel, Clock clock, Instant expiresAt, Runnable closeStream) {
        this.channel = channel;
        this.clock = clock;
        this.expiresAt = expiresAt;
        this.closeStream = closeStream;
    }

    /**
     * Arms the timer for the stream just started on {@code channel}.
     *
     * @param channel the stream's channel
     * @param clock the server's clock, the one that judges the token
     * @param expiresAt when the credentials of the request that opened the stream expire, or {@code null}
     *     for a caller whose credentials do not
     * @param closeStream closes the stream gracefully; runs on the channel's event loop
     * @return the armed timer, to cancel when the stream ends; {@code null} if nothing was armed
     */
    static @Nullable SseExpiry arm(Channel channel, Clock clock, @Nullable Instant expiresAt, Runnable closeStream) {
        if (expiresAt == null) {
            return null;
        }
        final var expiry = new SseExpiry(channel, clock, expiresAt, closeStream);
        return expiry.schedule() ? expiry : null;
    }

    /** Drops the timer, on the event loop. Idempotent, and harmless after it fired. */
    void cancel() {
        final var pending = timer;
        timer = null;
        if (pending != null) {
            pending.cancel(false);
        }
    }

    private boolean schedule() {
        final long delayNanos;
        try {
            delayNanos =
                    Math.max(0, Duration.between(clock.instant(), expiresAt).toNanos());
        } catch (ArithmeticException tooFarAhead) {
            return false;
        }
        timer = channel.eventLoop().schedule(this::fire, delayNanos, TimeUnit.NANOSECONDS);
        return true;
    }

    /**
     * The timer runs on the monotonic clock and the expiry is judged by the server's clock: when the
     * two disagree and it fires a little early, it waits out the rest instead of closing early.
     */
    private void fire() {
        timer = null;
        if (clock.instant().isBefore(expiresAt)) {
            schedule();
        } else {
            closeStream.run();
        }
    }
}
