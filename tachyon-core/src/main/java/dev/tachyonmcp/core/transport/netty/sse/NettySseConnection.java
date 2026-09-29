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
import io.netty.util.concurrent.ScheduledFuture;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link SseConnection} backed by a Netty {@link Channel}. Writes SSE frames
 * as chunked HTTP content and registers a close listener for cleanup.
 *
 * <p>Events are never dropped from an open stream. While the channel is unwritable (a slow
 * client), sent events are held and written once {@link #onWritabilityChanged()} reports it
 * writable again. The stream closes instead — and the client resumes from the last event it
 * received with {@code Last-Event-ID} — when it stays unwritable past the stall timeout, or when the
 * held events exceed the byte budget.
 *
 * <p>A resuming connection starts in replay mode: live events are held until {@link #replay} has
 * queued the missed backlog, so a live event can never overtake (and advance the client's
 * {@code Last-Event-ID} past) an event still waiting to be replayed. The byte budget applies once
 * the backlog is queued: a slow backlog read must not close the stream, or the client would resume
 * into the same replay again.
 */
public final class NettySseConnection implements SseConnection {

    private static final Logger logger = LoggerFactory.getLogger(NettySseConnection.class);

    private final Channel channel;
    private final Duration stallTimeout;
    private final long maxHeldBytes;

    // Event-loop confined.
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private long heldBytes;
    private boolean replaying;
    private boolean flushing;
    private boolean closing;
    private @Nullable ScheduledFuture<?> stallTimer;

    /** A queued event; {@code liveBytes} is 0 for replayed events, which the budget never counts. */
    private record Pending(SseEvent event, int liveBytes) {}

    /** A connection with no stall timer that holds up to the channel's write high watermark. */
    public NettySseConnection(Channel channel, final Runnable onCloseAction) {
        this(channel, onCloseAction, false, Duration.ZERO, 0);
    }

    /**
     * @param replaying       {@code true} to hold live events until {@link #replay} completes
     * @param stallTimeout    how long the channel may stay unwritable before the stream closes;
     *                        {@link Duration#ZERO} disables it
     * @param maxPendingBytes encoded live events held while the channel is unwritable; {@code 0}
     *                        uses the channel's write high watermark
     */
    public NettySseConnection(
            Channel channel,
            final Runnable onCloseAction,
            boolean replaying,
            Duration stallTimeout,
            int maxPendingBytes) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.stallTimeout = Objects.requireNonNull(stallTimeout, "stallTimeout");
        this.maxHeldBytes =
                maxPendingBytes > 0 ? maxPendingBytes : channel.config().getWriteBufferHighWaterMark();
        this.replaying = replaying;
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
        onEventLoop(() -> {
            if (closing || !channel.isActive()) return;
            // Nothing held and writable: skip measuring and queueing, the common case.
            if (!replaying && pending.isEmpty() && channel.isWritable()) {
                write(event);
                channel.flush();
                if (!channel.isWritable()) armStallTimer();
                return;
            }
            var size = SseSerializer.measuredLength(SseSerializer.measure(event));
            pending.add(new Pending(event, size));
            heldBytes += size;
            if (!replaying) {
                flush();
                abortIfOverBudget();
            }
        });
    }

    /** Writes {@code event} ahead of any held live events; used for the opening priming event. */
    void prime(SseEvent event) {
        onEventLoop(() -> {
            if (closing) return;
            write(event);
            channel.flush();
        });
    }

    /**
     * Queues the missed events ahead of the held live events, skipping live events the replay
     * already covers, then leaves replay mode and writes as much as the channel accepts.
     */
    void replay(List<SseEvent> missed) {
        onEventLoop(() -> {
            if (closing) return;
            var replayedUpTo = Long.MIN_VALUE;
            for (var event : missed) {
                replayedUpTo = Math.max(replayedUpTo, sequenceOf(event));
            }
            var live = new ArrayDeque<>(pending);
            pending.clear();
            missed.forEach(event -> pending.add(new Pending(event, 0)));
            for (var held : live) {
                if (sequenceOf(held.event()) <= replayedUpTo) {
                    heldBytes -= held.liveBytes();
                } else {
                    pending.add(held);
                }
            }
            replaying = false;
            flush();
            abortIfOverBudget();
        });
    }

    /**
     * Reacts to a channel writability change: writable again cancels the stall timer and resumes
     * writing held events; unwritable starts the stall timer. Called on the event loop.
     */
    public void onWritabilityChanged() {
        onEventLoop(() -> {
            if (closing) return;
            if (channel.isWritable()) {
                cancelStallTimer();
                if (!replaying) flush();
            } else {
                armStallTimer();
            }
        });
    }

    @Override
    public void close() {
        onEventLoop(this::doClose);
    }

    private void onEventLoop(Runnable task) {
        var eventLoop = channel.eventLoop();
        if (eventLoop.inEventLoop()) {
            task.run();
        } else {
            eventLoop.execute(task);
        }
    }

    private void flush() {
        // A flush can report the channel writable again and re-enter via onWritabilityChanged.
        if (flushing) return;
        flushing = true;
        try {
            while (!closing && !pending.isEmpty() && channel.isWritable()) {
                while (!pending.isEmpty() && channel.isWritable()) {
                    var next = pending.poll();
                    assert next != null;
                    heldBytes -= next.liveBytes();
                    write(next.event());
                }
                channel.flush();
            }
        } finally {
            flushing = false;
        }
        if (!closing && !channel.isWritable()) armStallTimer();
    }

    private void abortIfOverBudget() {
        if (closing || heldBytes <= maxHeldBytes) return;
        // One live event larger than the budget may still wait on its own.
        if (pending.stream().filter(held -> held.liveBytes() > 0).count() > 1) {
            abort("held SSE events exceed " + maxHeldBytes + " bytes");
        }
    }

    private void write(SseEvent event) {
        if (!channel.isActive()) return;
        var buf = SseSerializer.encode(channel.alloc(), event);
        channel.write(new DefaultHttpContent(buf)).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
    }

    private void armStallTimer() {
        if (stallTimeout.isZero() || stallTimer != null || closing || !channel.isActive()) return;
        stallTimer = channel.eventLoop().schedule(this::onStall, stallTimeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    private void cancelStallTimer() {
        if (stallTimer != null) {
            stallTimer.cancel(false);
            stallTimer = null;
        }
    }

    private void onStall() {
        stallTimer = null;
        if (closing || channel.isWritable()) return;
        abort("unwritable for " + stallTimeout);
    }

    /**
     * Closes at once, discarding unsent output: a stalled peer would never drain a graceful close.
     * The client's {@code Last-Event-ID} is the last complete event it received (an SSE parser
     * never dispatches a partial one), so it resumes without a gap.
     */
    private void abort(String reason) {
        logger.debug("Closing slow SSE stream {}: {}", channel.remoteAddress(), reason);
        closing = true;
        discardPending();
        channel.close();
    }

    private void doClose() {
        discardPending();
        if (closing) return;
        closing = true;
        if (channel.isActive()) {
            SseHeartbeat.cancel(channel);
            channel.write(new DefaultHttpContent(
                    ByteBufUtil.writeUtf8(channel.alloc(), "retry: " + SSE_RETRY_DELAY_MS + "\n")));
            channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).addListener(ChannelFutureListener.CLOSE);
        }
    }

    private void discardPending() {
        pending.clear();
        heldBytes = 0;
        cancelStallTimer();
    }

    /** The global event counter value of a wire id ({@code <n>} or {@code <n>#<streamKey>}). */
    private static long sequenceOf(SseEvent event) {
        var id = event.id();
        var hash = id.indexOf('#');
        try {
            return Long.parseLong(hash < 0 ? id : id.substring(0, hash));
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }
}
