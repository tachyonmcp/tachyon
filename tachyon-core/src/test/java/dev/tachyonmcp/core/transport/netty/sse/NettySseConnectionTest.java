/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty.sse;

import static dev.tachyonmcp.core.test.TestUtils.newEngine;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.server.domain.RequestId;
import dev.tachyonmcp.core.runtime.SseEvent;
import dev.tachyonmcp.core.server.config.SessionConfig;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.server.session.SessionEvent;
import dev.tachyonmcp.core.transport.netty.sse.SseManager.ResumeCursor;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the replay gate and slow-client handling of {@link NettySseConnection}: an E2E
 * client can neither pause the replay mid-way nor make a loopback socket stop draining on cue.
 */
class NettySseConnectionTest {

    private static final Pattern SSE_ID = Pattern.compile("^id: (\\S+)$", Pattern.MULTILINE);
    private static final Duration STALL_TIMEOUT = Duration.ofSeconds(30);

    private final List<EmbeddedChannel> channels = new ArrayList<>();

    @AfterEach
    void tearDown() {
        channels.forEach(EmbeddedChannel::finishAndReleaseAll);
    }

    @Test
    void liveEventsWaitForReplayAndSkipReplayedDuplicates() {
        var channel = newChannel(Integer.MAX_VALUE);
        var connection = resuming(channel);

        connection.prime(event("5"));
        // Live events arriving while the backlog is being read; 7 is also in the replay snapshot.
        connection.send(event("7"));
        connection.send(event("8"));
        assertThat(sentIds(channel))
                .as("live events must not overtake the backlog: a drop now would skip it")
                .containsExactly("5");

        connection.replay(List.of(event("6"), event("7")));
        connection.send(event("9"));

        assertThat(sentIds(channel))
                .as("backlog first, then held live events without the replayed duplicate, then live")
                .containsExactly("6", "7", "8", "9");
        assertThat(channel.isActive()).isTrue();
    }

    @Test
    void burstPausesReplayAndResumesWhenWritableAgain() {
        // Priming plus one replayed event fill the write buffer.
        var channel = newChannel(2);
        var connection = resuming(channel);

        connection.prime(event("5"));
        connection.replay(List.of(event("6"), event("7"), event("8")));
        connection.send(event("9"));

        assertThat(sentIds(channel))
                .as("writing pauses at the first event that no longer fits")
                .containsExactly("5", "6");
        assertThat(channel.isActive())
                .as("a burst is not a stall: the stream stays open")
                .isTrue();

        writable(channel, connection, true);

        assertThat(sentIds(channel))
                .as("the rest of the backlog, then the held live event, in order")
                .containsExactly("7", "8", "9");
        assertThat(channel.isActive()).isTrue();
    }

    @Test
    void stallPastTimeoutClosesAtLastDeliveredEventAndResumesFromIt() {
        try (ServerEngine server = newEngine(b -> b.session(SessionConfig.Builder::enabled))) {
            server.createSession("sess_slow");
            for (long id = 6; id <= 8; id++) {
                server.appendEvent(new SessionEvent.ResponseEvent(
                        "sess_slow", RequestId.of(id), "{\"n\":" + id + "}", 1000L + id, id, null));
            }
            var manager = new SseManager(server);

            var slow = newChannel(2);
            var first = resuming(slow);
            first.prime(event("5"));
            first.replay(manager.missedEvents("sess_slow", cursor("5")));
            assertThat(sentIds(slow)).containsExactly("5", "6");

            elapse(slow, STALL_TIMEOUT.minusMillis(1));
            assertThat(slow.isActive()).as("still inside the grace period").isTrue();

            elapse(slow, Duration.ofMillis(1));
            assertThat(slow.isActive())
                    .as("unwritable past the stall timeout: close so the client resumes")
                    .isFalse();
            assertThat(drain(slow))
                    .as("closed at once: a stalled peer would never drain a graceful close")
                    .isEmpty();
            first.send(event("10"));
            assertThat(drain(slow)).isEmpty();

            // The client resumes from the last event it received: nothing is lost.
            var resumed = newChannel(Integer.MAX_VALUE);
            var second = resuming(resumed);
            second.prime(event("6"));
            second.replay(manager.missedEvents("sess_slow", cursor("6")));

            assertThat(sentIds(resumed)).containsExactly("6", "7", "8");
            assertThat(resumed.isActive()).isTrue();
        }
    }

    @Test
    void recoveringBeforeTheStallTimeoutKeepsTheStreamOpen() {
        var channel = newChannel(1);
        var connection = resuming(channel);
        connection.prime(event("5"));
        connection.replay(List.of(event("6")));
        assertThat(sentIds(channel)).containsExactly("5");

        elapse(channel, STALL_TIMEOUT.dividedBy(2));
        writable(channel, connection, true);
        elapse(channel, STALL_TIMEOUT);

        assertThat(channel.isActive())
                .as("writable again before the deadline cancels the stall timer")
                .isTrue();
        assertThat(sentIds(channel)).containsExactly("6");
    }

    @Test
    void liveEventsBeyondTheByteBudgetCloseTheStream() {
        var channel = newChannel(1);
        var connection = new NettySseConnection(channel, () -> {}, false, STALL_TIMEOUT, 100);
        connection.prime(event("5"));

        connection.send(new SseEvent("6", "message", "x".repeat(80)));
        assertThat(channel.isActive())
                .as("one event may exceed the budget on its own")
                .isTrue();

        connection.send(new SseEvent("7", "message", "y".repeat(80)));
        assertThat(channel.isActive())
                .as("held events past the budget close the stream instead of growing memory")
                .isFalse();
        assertThat(sentIds(channel)).containsExactly("5");
    }

    @Test
    void liveEventsHeldDuringReplayAreBudgetedOnlyAfterReplay() {
        var channel = newChannel(Integer.MAX_VALUE);
        var connection = new NettySseConnection(channel, () -> {}, true, STALL_TIMEOUT, 100);
        connection.prime(event("5"));

        connection.send(new SseEvent("7", "message", "x".repeat(80)));
        connection.send(new SseEvent("8", "message", "y".repeat(80)));
        assertThat(channel.isActive())
                .as("a slow backlog read must not abort the stream: the client would resume into it again")
                .isTrue();

        connection.replay(List.of(event("6")));

        assertThat(sentIds(channel))
                .as("the writable channel drains backlog and held live events after replay")
                .containsExactly("5", "6", "7", "8");
        assertThat(channel.isActive()).isTrue();
    }

    @Test
    void liveEventsBeyondTheByteBudgetCloseTheStreamOnceReplayEnds() {
        var channel = newChannel(2);
        var connection = new NettySseConnection(channel, () -> {}, true, STALL_TIMEOUT, 100);
        connection.prime(event("5"));
        connection.send(new SseEvent("7", "message", "x".repeat(80)));
        connection.send(new SseEvent("8", "message", "y".repeat(80)));

        connection.replay(List.of(event("6")));

        assertThat(sentIds(channel))
                .as("the backlog is written before the budget closes the stream, so each resume progresses")
                .containsExactly("5", "6");
        assertThat(channel.isActive())
                .as("held live events still past the budget after replay close the stream")
                .isFalse();
    }

    @Test
    void oneOversizedLiveEventMayWaitBehindTheBacklog() {
        var channel = newChannel(1);
        var connection = new NettySseConnection(channel, () -> {}, true, STALL_TIMEOUT, 100);
        connection.prime(event("5"));
        connection.replay(List.of(event("6"), event("7")));

        connection.send(new SseEvent("8", "message", "x".repeat(200)));

        assertThat(channel.isActive())
                .as("replayed events do not count as held live events")
                .isTrue();
        writable(channel, connection, true);
        assertThat(sentIds(channel)).containsExactly("5", "6", "7", "8");
    }

    private EmbeddedChannel newChannel(int writableContents) {
        var channel = new EmbeddedChannel(new ChokeAfter(writableContents));
        channel.freezeTime();
        channels.add(channel);
        return channel;
    }

    private static NettySseConnection resuming(EmbeddedChannel channel) {
        return new NettySseConnection(channel, () -> {}, true, STALL_TIMEOUT, 64 * 1024);
    }

    /** Flips writability and delivers the event, as {@code McpOperationHandler} does. */
    private static void writable(EmbeddedChannel channel, NettySseConnection connection, boolean writable) {
        Objects.requireNonNull(channel.unsafe().outboundBuffer()).setUserDefinedWritability(1, writable);
        connection.onWritabilityChanged();
    }

    private static void elapse(EmbeddedChannel channel, Duration duration) {
        channel.advanceTimeBy(duration.toNanos(), TimeUnit.NANOSECONDS);
        channel.runScheduledPendingTasks();
    }

    private static SseEvent event(String id) {
        return new SseEvent(id, "message", "{}");
    }

    private static ResumeCursor cursor(String lastEventId) {
        return Objects.requireNonNull(ResumeCursor.parse(lastEventId));
    }

    private static List<String> sentIds(EmbeddedChannel channel) {
        var ids = new ArrayList<String>();
        for (var frame : drain(channel)) {
            var matcher = SSE_ID.matcher(frame);
            if (matcher.find()) ids.add(matcher.group(1));
        }
        return ids;
    }

    private static List<String> drain(EmbeddedChannel channel) {
        var frames = new ArrayList<String>();
        Object msg;
        while ((msg = channel.readOutbound()) != null) {
            try {
                if (msg instanceof LastHttpContent) {
                    frames.add("<last>");
                } else if (msg instanceof HttpContent content) {
                    frames.add(content.content().toString(StandardCharsets.UTF_8));
                }
            } finally {
                ReferenceCountUtil.release(msg);
            }
        }
        return frames;
    }

    /** Turns the channel unwritable once {@code limit} contents are written, like a slow client. */
    private static final class ChokeAfter extends ChannelOutboundHandlerAdapter {

        private final int limit;
        private int written;

        ChokeAfter(int limit) {
            this.limit = limit;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            ctx.write(msg, promise);
            if (msg instanceof HttpContent && ++written == limit) {
                var buffer = Objects.requireNonNull(ctx.channel().unsafe().outboundBuffer());
                buffer.setUserDefinedWritability(1, false);
            }
        }
    }
}
