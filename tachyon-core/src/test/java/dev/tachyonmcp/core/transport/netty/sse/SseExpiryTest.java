/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty.sse;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * An SSE stream is closed when the token of the request that opened it expires: at that instant and
 * never before, by one timer that dies with the stream, and not at all for a caller whose credentials
 * do not expire. The opener passes the expiry of its own caller; nothing is read from the channel. L4
 * of the authorization spec is a Tachyon decision; MCP itself is silent.
 */
class SseExpiryTest {

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    private final EmbeddedChannel channel = new EmbeddedChannel();
    private final AtomicInteger closed = new AtomicInteger();
    /** The server's clock, which judges the token; the channel's own time drives the timer. */
    private final AtomicReference<Instant> now = new AtomicReference<>(NOW);

    private final Clock clock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };

    @AfterEach
    void releaseChannel() {
        channel.finishAndReleaseAll();
    }

    private void arm(@Nullable Instant expiresAt) {
        SseExpiry.arm(channel, clock, expiresAt, closed::incrementAndGet);
    }

    /** Advances the timer's time and, by {@code clockMillis}, the server's clock. */
    private void advance(long timerMillis, long clockMillis) {
        now.updateAndGet(instant -> instant.plusMillis(clockMillis));
        channel.advanceTimeBy(timerMillis, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
    }

    @Test
    void closesTheStreamAtTheExpiryAndNotBefore() {
        channel.freezeTime();
        arm(NOW.plusSeconds(10));

        advance(9_999, 9_999);
        assertThat(closed).as("not a moment early").hasValue(0);

        advance(1, 1);
        assertThat(closed).hasValue(1);
    }

    /** The timer counts monotonic time; if it fires while the server's clock is still short, it waits. */
    @Test
    void aTimerThatFiresBeforeTheServersClockReachesTheExpiryWaitsOutTheRest() {
        channel.freezeTime();
        arm(NOW.plusSeconds(10));

        advance(10_000, 9_998);
        assertThat(closed).as("the server's clock says the token is still good").hasValue(0);

        advance(2, 2);
        assertThat(closed).hasValue(1);
    }

    @Test
    void aCallerWhoseCredentialsDoNotExpireClosesNothingAndSchedulesNothing() {
        arm(null);

        assertThat(channel.runScheduledPendingTasks()).as("no timer at all").isEqualTo(-1);
        assertThat(closed).hasValue(0);
    }

    @Test
    void anExpiryAlreadyPastClosesAtOnce() {
        arm(NOW.minusSeconds(1));

        channel.runScheduledPendingTasks();

        assertThat(closed).hasValue(1);
    }

    @Test
    void theTimerDiesWithTheChannel() {
        channel.freezeTime();
        arm(NOW.plusSeconds(10));

        channel.close();
        channel.advanceTimeBy(1, TimeUnit.DAYS);
        channel.runScheduledPendingTasks();

        assertThat(closed).as("a stream that ended has nothing left to close").hasValue(0);
    }

    @Test
    void anExpiryBeyondWhatATimerCanCountIsNeverReached() {
        arm(Instant.MAX);

        assertThat(channel.runScheduledPendingTasks()).isEqualTo(-1);
        assertThat(closed).hasValue(0);
    }
}
