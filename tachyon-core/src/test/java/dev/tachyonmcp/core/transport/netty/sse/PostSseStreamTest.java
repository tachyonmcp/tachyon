/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty.sse;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.core.runtime.SseEvent;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.transport.netty.ChannelHandlerUtils;
import dev.tachyonmcp.core.transport.netty.http.CorsDecision;
import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledHeapByteBuf;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
    private TrackingAllocator allocator;

    @BeforeEach
    void setUp() {
        sink = new CapturingSink();
        channel = new EmbeddedChannel(sink);
        allocator = new TrackingAllocator();
        channel.config().setAllocator(allocator);
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
    void failedWriteOfAnEarlierQueuedEventIsRecordedAndClosesTheChannel() {
        var stream = newStream(Duration.ZERO);
        stream.writeEvent(new SseEvent(ServerEngine.wireEventId(1, stream.streamKey()), "message", "first"));
        stream.writeEvent(new SseEvent(ServerEngine.wireEventId(2, stream.streamKey()), "message", "second"));
        sink.failContentWrite = 0;
        sink.completeHeaders = true;

        var start = stream.start().toCompletableFuture();

        assertThat(ChannelHandlerUtils.closeFailure(channel))
                .as("only the last queued write used to carry the failure listener, so this one"
                        + " failed silently and onClose reported an ordinary disconnect")
                .isInstanceOf(IOException.class)
                .hasMessage("write failed");
        assertThat(channel.isActive()).isFalse();
        assertThat(start).isCompletedExceptionally();
        assertThat(start.exceptionNow()).isSameAs(ChannelHandlerUtils.closeFailure(channel));
    }

    @Test
    void startWaitsForAllInitialWritesAndPreservesAnEarlierFailure() {
        var stream = newStream(Duration.ZERO);
        stream.writeEvent(new SseEvent("2#1", "message", "ack"));
        stream.writeEvent(new SseEvent("3#1", "message", "notification"));
        var start = stream.start().toCompletableFuture();
        var failure = new IOException("ack failed after notification write completed");

        sink.pending.getLast().setSuccess();
        assertThat(start).isNotDone();
        sink.pending.getFirst().setSuccess();
        assertThat(start).isNotDone();
        sink.pending.get(1).setFailure(failure);

        assertThat(start).isCompletedExceptionally();
        assertThat(start.exceptionNow()).isSameAs(failure);
        assertThat(ChannelHandlerUtils.closeFailure(channel)).isSameAs(failure);
        assertThat(channel.isActive()).isFalse();
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

    @Test
    void failedHeartbeatWriteIsRecordedAsTheCloseCause() {
        var stream = newStream(Duration.ofMillis(10));
        sink.completeHeaders = true;
        stream.start();
        var closeCause = new ArrayList<Throwable>();
        stream.onClose(closeCause::add);
        sink.failWrites = true;

        channel.advanceTimeBy(1, TimeUnit.SECONDS);
        channel.runScheduledPendingTasks();

        assertThat(channel.isActive()).isFalse();
        assertThat(ChannelHandlerUtils.closeFailure(channel))
                .as("a heartbeat is how an idle stream finds a dead peer; without the cause every"
                        + " listener reads that failure as an ordinary disconnect")
                .isInstanceOf(IOException.class)
                .hasMessage("write failed");
        assertThat(closeCause).singleElement().isSameAs(ChannelHandlerUtils.closeFailure(channel));
    }

    @Test
    void terminateAsyncReportsAFailedTerminatingWrite() {
        var stream = newStream(Duration.ZERO);
        sink.completeHeaders = true;
        stream.start();
        sink.failWrites = true;

        var completion = stream.terminateAsync();

        assertThat(channel.isActive()).isFalse();
        assertThat(completion.isSuccess())
                .as("the failed write closes the channel from its own listener, so the close-future"
                        + " fallback fires first and used to report success for it")
                .isFalse();
        assertThat(completion.cause())
                .isSameAs(ChannelHandlerUtils.closeFailure(channel))
                .isInstanceOf(IOException.class)
                .hasMessage("write failed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"event", "offer", "comment", "before-start"})
    void pendingWritesAreBoundedUntilFlushCompletes(String kind) {
        final var stream = newStream(Duration.ZERO);
        if (!kind.equals("before-start")) stream.start();
        final var payload = "x".repeat(8192);
        for (int i = 0; i < 256; i++) {
            switch (kind) {
                case "event", "before-start" -> stream.writeEvent(new SseEvent("1", "message", payload));
                case "offer" -> stream.offerEvent(new SseEvent("1", "message", payload));
                case "comment" -> stream.comment(payload);
                default -> throw new AssertionError(kind);
            }
        }
        assertThat(channel.isActive())
                .as("the overflow close is a loop task, never run inside the caller's write")
                .isTrue();
        channel.runPendingTasks();
        assertThat(channel.isActive()).isFalse();
        assertThat(ChannelHandlerUtils.closeFailure(channel))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("SSE pending write limit");
        assertThat(sink.writes)
                .as("about 1 MiB of 8 KiB writes, never the whole 2 MiB burst")
                .hasSizeBetween(kind.equals("before-start") ? 0 : 100, 130);
        assertThat(allocator.leaked())
                .as("rejected and discarded buffers are released")
                .isEmpty();
        if (kind.equals("before-start")) {
            assertThat(sink.writes).isEmpty();
            assertThat(stream.start().toCompletableFuture()).isCompletedExceptionally();
            assertThat(stream.start().toCompletableFuture().exceptionNow())
                    .isSameAs(ChannelHandlerUtils.closeFailure(channel));
        }
    }

    @Test
    void finalResponseIsAdmittedPastAFullBudgetWithoutClosingTheStream() {
        final var stream = newStream(Duration.ZERO);
        stream.start();
        final var payload = "x".repeat(8192);
        for (int i = 0; i < 125; i++) {
            stream.writeEvent(new SseEvent(String.valueOf(i), "message", payload));
        }
        final var writesBefore = sink.writes.size();
        final var dropped = new AtomicInteger();
        final var body = "y".repeat(64 * 1024).getBytes(StandardCharsets.UTF_8);

        stream.writeEvent(200, body, dropped::incrementAndGet);
        channel.runPendingTasks();

        assertThat(channel.isActive())
                .as("a slow client still reading must get the result, not a pending-limit close")
                .isTrue();
        assertThat(ChannelHandlerUtils.closeFailure(channel)).isNull();
        assertThat(dropped).hasValue(0);
        assertThat(sink.writes).hasSize(writesBefore + 1);
        assertThat(sink.writes.getLast()).contains("data: " + new String(body, StandardCharsets.UTF_8));
        stream.writeEvent(new SseEvent("late", "message", payload));
        channel.runPendingTasks();
        assertThat(channel.isActive())
                .as("the final response does not lift the budget for later writes")
                .isFalse();
        assertThat(allocator.leaked()).isEmpty();
    }

    @Test
    void completingWritesRestoresCapacityAndAllowsALargeFinalResponse() {
        final var stream = newStream(Duration.ZERO);
        stream.start();
        sink.completePending();
        for (int i = 0; i < 128; i++) {
            stream.writeEvent(new SseEvent(String.valueOf(i), "message", "x".repeat(8192)));
            stream.comment("still working");
            sink.completePending();
            assertThat(channel.isActive()).isTrue();
        }
        stream.comment("done");
        final var dropped = new AtomicInteger();
        final var body = "x".repeat(2 * 1024 * 1024).getBytes(StandardCharsets.UTF_8);
        stream.writeEvent(200, body, dropped::incrementAndGet);
        sink.completePending();

        assertThat(channel.isActive()).isTrue();
        assertThat(dropped).hasValue(0);
        assertThat(sink.writes.getLast()).contains("data: " + new String(body, StandardCharsets.UTF_8));
        assertThat(ChannelHandlerUtils.closeFailure(channel)).isNull();
        assertThat(allocator.leaked()).isEmpty();
    }

    @Test
    void closingBeforeStartDropsQueuedFinalResponseExactlyOnce() {
        final var stream = newStream(Duration.ZERO);
        final var dropped = new AtomicInteger();
        stream.writeEvent(1, "{}".getBytes(StandardCharsets.UTF_8), dropped::incrementAndGet);

        stream.terminate();
        channel.close();

        assertThat(dropped).hasValue(1);
        assertThat(stream.start().toCompletableFuture()).isCompletedExceptionally();
        assertThat(sink.writes).isEmpty();
        assertThat(allocator.leaked())
                .as("the queued response buffer is released")
                .isEmpty();
    }

    private PostSseStream newStream(Duration heartbeatInterval) {
        return new PostSseStream(channel, CorsDecision.NONE, eventIds::incrementAndGet, heartbeatInterval);
    }

    /** Hands out unpooled heap buffers and remembers them, so a test can spot one never released. */
    private static final class TrackingAllocator extends AbstractByteBufAllocator {

        private final List<ByteBuf> allocated = new ArrayList<>();

        @Override
        protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
            var buf = new UnpooledHeapByteBuf(this, initialCapacity, maxCapacity);
            allocated.add(buf);
            return buf;
        }

        @Override
        protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) {
            return newHeapBuffer(initialCapacity, maxCapacity);
        }

        @Override
        public boolean isDirectBufferPooled() {
            return false;
        }

        List<ByteBuf> leaked() {
            return allocated.stream().filter(buf -> buf.refCnt() > 0).toList();
        }
    }

    /** Records outbound writes and, unless told to fail them, leaves their promises pending. */
    private static final class CapturingSink extends ChannelOutboundHandlerAdapter {

        private final List<String> writes = new ArrayList<>();
        private final List<ChannelPromise> pending = new ArrayList<>();
        private boolean failWrites;
        private boolean completeHeaders;
        private int failContentWrite = -1;
        private int contentWrites;

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (msg instanceof LastHttpContent) {
                writes.add("LAST");
            } else if (msg instanceof HttpContent content) {
                writes.add(content.content().toString(StandardCharsets.UTF_8));
            } else {
                writes.add(msg.getClass().getSimpleName());
            }
            var contentIndex = msg instanceof HttpContent ? contentWrites++ : -1;
            ReferenceCountUtil.release(msg);
            if (failWrites || (contentIndex >= 0 && contentIndex == failContentWrite)) {
                promise.setFailure(new IOException("write failed"));
            } else if (completeHeaders && !(msg instanceof HttpContent)) {
                promise.setSuccess();
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
