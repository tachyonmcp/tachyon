/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty.sse;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.core.runtime.SseEvent;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the parts of {@link PostSseStream} an E2E run cannot pin down: wire-id ordering
 * against the event-id counter, and the write outcomes {@code start()} reports. The sink leaves
 * write promises pending on purpose, which is what keeps the "flush has not landed yet" window
 * open long enough to assert on.
 */
class PostSseStreamTest {

    private static final Pattern SSE_ID = Pattern.compile("id: (\\d+)#(\\S+)");

    private CapturingSink sink;
    private EmbeddedChannel channel;
    private AtomicLong eventIds;

    @BeforeEach
    void setUp() {
        sink = new CapturingSink();
        channel = new EmbeddedChannel(sink);
        eventIds = new AtomicLong();
    }

    @AfterEach
    void tearDown() {
        channel.finishAndReleaseAll();
    }

    @Test
    void primingEventIdPrecedesEventIdsDrawnWhileStreamWasStillBuffered() {
        var stream = newStream(Duration.ZERO);
        // Mirrors DefaultTachyonServer#sendSerializedNotification: the notification's id is drawn
        // (and logged) while the POST is still buffered, before start() opens the stream.
        var notificationId = eventIds.incrementAndGet();
        stream.start();
        stream.writeEvent(new SseEvent(
                ServerEngine.wireEventId(notificationId, stream.streamKey()), "message", "{\"jsonrpc\":\"2.0\"}"));

        var ids = sink.eventIds();
        assertThat(ids).as("priming event plus the notification").hasSize(2);
        assertThat(ids)
                .as("Last-Event-ID must not go backwards: a client resuming from the priming id"
                        + " would otherwise never be sent the notification")
                .isSorted();
        assertThat(ids.getFirst())
                .as("the priming event reuses the stream key's draw as its baseline id")
                .isEqualTo(Long.parseLong(stream.streamKey()));
        assertThat(sink.streamKeys()).containsOnly(stream.streamKey());
    }

    @Test
    void startAfterTerminateFailsAsClosedChannel() {
        var stream = newStream(Duration.ZERO);
        stream.terminate();

        var start = stream.start().toCompletableFuture();

        assertThat(start).isCompletedExceptionally();
        assertThat(start.exceptionNow()).isInstanceOf(ClosedChannelException.class);
    }

    @Test
    void secondStartMirrorsTheFailureOfTheCallThatOpenedTheStream() {
        sink.failWrites = true;
        var stream = newStream(Duration.ZERO);

        var first = stream.start().toCompletableFuture();
        var second = stream.start().toCompletableFuture();

        assertThat(first).isCompletedExceptionally();
        assertThat(second)
                .as("a no-op start() must not report success for a flush that failed")
                .isCompletedExceptionally();
        assertThat(second.exceptionNow()).isSameAs(first.exceptionNow()).isInstanceOf(IOException.class);
    }

    @Test
    void startAfterSelfStartingCommentWaitsForThatFlush() {
        var stream = newStream(Duration.ZERO);
        stream.comment("keep-alive");

        var start = stream.start().toCompletableFuture();

        assertThat(start)
                .as("the comment opened the stream; its flush is still pending, so start() is too")
                .isNotDone();

        sink.completePending();

        assertThat(start).isCompleted();
    }

    @Test
    void terminateStopsHeartbeatsBeforeWritingTheFinalChunk() {
        var stream = newStream(Duration.ofMillis(10));
        stream.start();
        assertThat(SseHeartbeat.isEnabled(channel)).isTrue();

        stream.terminate();

        assertThat(SseHeartbeat.isEnabled(channel)).isFalse();
        var writesBefore = sink.writes.size();
        // The channel is still open: doClose's CLOSE listener waits on a flush the sink never
        // completes. Without the cancel, this tick would append a comment after the last chunk.
        channel.advanceTimeBy(1, TimeUnit.SECONDS);
        channel.runScheduledPendingTasks();

        assertThat(channel.isActive()).isTrue();
        assertThat(sink.writes).hasSize(writesBefore);
        assertThat(sink.writes.getLast()).isEqualTo("LAST");
    }

    private PostSseStream newStream(Duration heartbeatInterval) {
        return new PostSseStream(channel, null, eventIds::incrementAndGet, heartbeatInterval);
    }

    /** Records outbound writes and, unless told to fail them, leaves their promises pending. */
    private static final class CapturingSink extends ChannelOutboundHandlerAdapter {

        private final List<String> writes = new ArrayList<>();
        private final List<ChannelPromise> pending = new ArrayList<>();
        private boolean failWrites;

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (msg instanceof LastHttpContent) {
                writes.add("LAST");
            } else if (msg instanceof HttpContent content) {
                writes.add(content.content().toString(StandardCharsets.UTF_8));
            } else {
                writes.add(msg.getClass().getSimpleName());
            }
            ReferenceCountUtil.release(msg);
            if (failWrites) {
                promise.setFailure(new IOException("write failed"));
            } else {
                pending.add(promise);
            }
        }

        void completePending() {
            pending.forEach(ChannelPromise::setSuccess);
            pending.clear();
        }

        List<Long> eventIds() {
            return matches(1).stream().map(Long::valueOf).toList();
        }

        List<String> streamKeys() {
            return matches(2);
        }

        private List<String> matches(int group) {
            var found = new ArrayList<String>();
            for (var write : writes) {
                var matcher = SSE_ID.matcher(write);
                while (matcher.find()) {
                    found.add(matcher.group(group));
                }
            }
            return found;
        }
    }
}
