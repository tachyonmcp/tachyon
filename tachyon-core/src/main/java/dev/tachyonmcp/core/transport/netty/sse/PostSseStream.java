/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty.sse;

import static dev.tachyonmcp.core.transport.netty.sse.SseManager.SSE_RETRY_DELAY_MS;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.core.runtime.SseEvent;
import dev.tachyonmcp.core.server.OutboundSseStream;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.transport.netty.ChannelHandlerUtils;
import dev.tachyonmcp.core.transport.netty.http.CorsDecision;
import dev.tachyonmcp.core.transport.netty.http.HttpHelpers;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.concurrent.FastThreadLocalThread;
import io.netty.util.concurrent.PromiseCombiner;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-POST SSE response stream. Lazily upgrades the POST response from JSON to an SSE stream
 * when the dispatching handler emits a server-to-client message.
 *
 * <p>All state transitions and queued-event mutations are serialized onto the channel's
 * {@link io.netty.channel.EventLoop}. The lifecycle state is volatile for producer admission and
 * {@link #started()}; only the event loop writes it.
 *
 * <p>Producers measure each write's exact encoded size, reserve it, then encode it on their own
 * thread; the reservation is held until the write completes, and the event loop only queues or
 * writes the ready buffer. A producer waiting for capacity holds no buffer. The budget is {@code
 * NetworkConfig#maxPendingSseBytes}; one larger event may exceed it, so a budget of {@code 0}
 * allows exactly one write in flight. A producer off Netty's I/O threads waits for capacity, so a
 * fast tool slows to the client's pace; a stalled client is closed by the writer idle timeout,
 * which wakes it. An interrupted producer closes the stream rather than drop its event silently.
 * {@link #offerEvent} and any write from a Netty I/O thread (this channel's loop or another
 * client's) never wait: past the budget, or the channel's write high watermark when the budget is
 * {@code 0}, they close the connection instead. The final response skips the budget and is
 * encoded before reserving: it is the last write and never waits, so it adds at most one event. The event loop never blocks,
 * and the task queue stays bounded.
 */
@InternalApi
public final class PostSseStream implements OutboundSseStream {

    private enum State {
        NEW(false, false),
        OPEN(true, false),
        CLOSED_UNOPENED(false, true),
        CLOSED_OPENED(true, true);

        private final boolean opened;
        private final boolean closed;

        State(boolean opened, boolean closed) {
            this.opened = opened;
            this.closed = closed;
        }
    }

    private static final Logger logger = LoggerFactory.getLogger(PostSseStream.class);

    private final Channel channel;
    private final CorsDecision cors;
    private final long streamKeyId;
    private final String streamKey;
    private final Duration heartbeatInterval;

    private static final int TASK_OVERHEAD_BYTES = 128;

    /** How a write that finds the budget full is admitted. */
    private enum Admission {
        /** Park the producer until capacity frees up; on a Netty I/O thread, admitted like {@link #OFFER}. */
        WAIT,
        /** Never park: overflow-close instead. Limited by the high watermark when the budget is 0. */
        OFFER,
        /** Always admitted: the final response is the stream's last write. */
        FINAL
    }

    private record PendingWrite(ByteBuf buf, ChannelPromise promise) {}

    private final ArrayDeque<PendingWrite> queued = new ArrayDeque<>();
    private final AtomicLong pendingBytes = new AtomicLong();
    private final ReentrantLock capacityLock = new ReentrantLock();
    private final Condition capacityFreed = capacityLock.newCondition();
    private final AtomicInteger capacityWaiters = new AtomicInteger();
    private final AtomicBoolean startRequested = new AtomicBoolean();
    private final long maxPendingBytes;
    private final long maxOfferedBytes;
    private final CompletableFuture<Void> startCompletion = new CompletableFuture<>();
    private final ChannelFutureListener channelClosed = ignored -> discardQueued();
    private volatile State state = State.NEW;
    private @Nullable ChannelFuture closeWrite;
    private final ChannelFutureListener writeFailureListener = this::closeOnWriteFailure;

    public PostSseStream(
            Channel channel,
            CorsDecision cors,
            LongSupplier eventIdSupplier,
            Duration heartbeatInterval,
            int maxPendingBytes) {
        this.channel = channel;
        this.cors = cors;
        this.heartbeatInterval = heartbeatInterval;
        this.maxPendingBytes = maxPendingBytes;
        // Offers never wait, so with buffering off they fall back to the channel's own writability
        // limit: a burst of notifications to a healthy subscriber must not disconnect it.
        this.maxOfferedBytes =
                maxPendingBytes > 0 ? maxPendingBytes : channel.config().getWriteBufferHighWaterMark();
        channel.closeFuture().addListener(channelClosed);
        // Session-unique key (one counter draw per POST) tagging this stream's events in the log
        // and suffixing its SSE ids, so Last-Event-ID resolves to THIS stream on replay. Not the
        // JSON-RPC request id — clients may reuse those across sequential requests. Drawn at POST
        // arrival, so it also serves as the priming event's id: lower than every event id drawn
        // later during dispatch, keeping this stream's wire ids ascending.
        this.streamKeyId = eventIdSupplier.getAsLong();
        this.streamKey = String.valueOf(streamKeyId);
    }

    @Override
    public String channelId() {
        return channel.id().asLongText();
    }

    @Override
    public String streamKey() {
        return streamKey;
    }

    @Override
    public CompletionStage<Void> start() {
        if (startRequested.compareAndSet(false, true)) {
            try {
                runOnEventLoop(this::doStart);
            } catch (RejectedExecutionException e) {
                startCompletion.completeExceptionally(e);
            }
        }
        return startCompletion;
    }

    @Override
    public boolean started() {
        return state.opened;
    }

    @Override
    public void writeEvent(@Nullable SseEvent event) {
        if (event == null || !admitting()) return;
        if (logger.isTraceEnabled()) {
            logger.trace("POST-SSE writing event, id={}, data={}", event.id(), abbreviate(event.data()));
        }
        writeMeasured(event, Admission.WAIT);
    }

    @Override
    public void offerEvent(@Nullable SseEvent event) {
        if (event == null || !admitting()) return;
        writeMeasured(event, Admission.OFFER);
    }

    /**
     * Writes a final response event, invoking {@code onDropped} if admission or delivery fails,
     * so the caller can re-deliver the buffered response to a reconnected stream. The callback may
     * run on the caller's thread when admission fails, or on the event loop after a failed write.
     * The response is admitted past a full budget without waiting: it is the stream's last write,
     * so it adds at most one event, and a slow client still reading must not lose it.
     */
    public void writeEvent(long sseEventId, byte[] body, @Nullable Runnable onDropped) {
        if (!admitting()) {
            if (onDropped != null) onDropped.run();
            return;
        }
        // Never waits for capacity, so there is nothing to measure first: encode, then reserve its size.
        final var buf = SseSerializer.encode(channel.alloc(), ServerEngine.wireEventId(sseEventId, streamKey), body);
        final long bytes = buf.readableBytes() + TASK_OVERHEAD_BYTES;
        if (!reserve(bytes, Admission.FINAL)) {
            buf.release();
            if (onDropped != null) onDropped.run();
            return;
        }
        dispatch(buf, bytes, false, onDropped);
    }

    @Override
    public void comment(@Nullable String message) {
        if (!admitting()) return;
        // SSE comment = a line starting with ':'. Flatten embedded line breaks so the message
        // cannot inject extra SSE lines/events. Blank/null → bare ':' heartbeat.
        String line = message == null || message.isBlank()
                ? ":\r\n"
                : ": " + message.replace('\r', ' ').replace('\n', ' ') + "\r\n";
        // Self-starting: a comment upgrades the buffered POST to SSE even when no event was written
        // yet — this is the token-free keep-alive path.
        final var length = ByteBufUtil.utf8Bytes(line);
        final long bytes = length + TASK_OVERHEAD_BYTES;
        if (!reserve(bytes, Admission.WAIT)) return;
        final var buf = allocateReserved(length, bytes);
        ByteBufUtil.reserveAndWriteUtf8(buf, line, length);
        dispatch(buf, bytes, true, null);
    }

    @Override
    public void close() {
        runOnEventLoopQuietly(() -> doClose(true), "close");
    }

    public void terminate() {
        runOnEventLoopQuietly(() -> doClose(false), "terminate");
    }

    /** Terminates the stream and completes after its final write succeeds or fails. */
    public ChannelFuture terminateAsync() {
        final var completion = channel.newPromise();
        // Fallback: a shutting-down event loop can accept the task below and never run it, which
        // would leave this promise — and the shutdown drain waiting on it — pending forever. A
        // failed terminating write closes the channel from its own listener, so this fires before
        // the one below and must not report success for it: the recorded cause tells the two apart.
        channel.closeFuture().addListener(f -> {
            var failure = ChannelHandlerUtils.closeFailure(channel);
            if (failure == null) {
                completion.trySuccess();
            } else {
                completion.tryFailure(failure);
            }
        });
        try {
            runOnEventLoop(() -> {
                try {
                    doClose(false).addListener(f -> {
                        if (f.isSuccess()) completion.trySuccess();
                        else completion.tryFailure(f.cause());
                    });
                } catch (RuntimeException e) {
                    completion.tryFailure(e);
                }
            });
        } catch (RuntimeException e) {
            completion.tryFailure(e);
        }
        return completion;
    }

    @Override
    public void onClose(Consumer<@Nullable Throwable> callback) {
        channel.closeFuture().addListener(f -> callback.accept(ChannelHandlerUtils.closeFailure(channel)));
    }

    /**
     * Fire-and-forget variant: a loop already shutting down rejects the task, and there is no
     * caller left to hand that to — the channel is going away with the server.
     */
    private void runOnEventLoopQuietly(Runnable task, String what) {
        try {
            runOnEventLoop(task);
        } catch (RejectedExecutionException e) {
            logger.debug("POST-SSE {} dropped, event loop shutting down: channel={}", what, channel.id());
        }
    }

    private void executeLater(Runnable task, String what) {
        try {
            channel.eventLoop().execute(task);
        } catch (RejectedExecutionException e) {
            logger.debug("POST-SSE {} dropped, event loop shutting down: channel={}", what, channel.id());
        }
    }

    private void runOnEventLoop(Runnable task) {
        var eventLoop = channel.eventLoop();
        if (eventLoop.inEventLoop()) {
            task.run();
        } else {
            eventLoop.execute(task);
        }
    }

    private boolean admitting() {
        return pendingBytes.get() >= 0 && !state.closed && channel.isActive();
    }

    private boolean reserve(long bytes, Admission admission) {
        final var parks = admission == Admission.WAIT && !onIoThread();
        final var limit = parks ? maxPendingBytes : maxOfferedBytes;
        while (channel.isActive() && !state.closed) {
            final var pending = pendingBytes.get();
            if (pending < 0) return false;
            if (admission == Admission.FINAL || (pending <= limit && (bytes > limit || bytes <= limit - pending))) {
                if (pendingBytes.compareAndSet(pending, pending + bytes)) return true;
            } else if (parks) {
                if (!awaitCapacity(pending)) {
                    // Interrupted (e.g. a cancelled tool): end the stream instead of leaving a
                    // silent gap. Admission stops first so no later event lands past the gap.
                    pendingBytes.set(-1);
                    signalCapacity();
                    close();
                    return false;
                }
            } else if (pendingBytes.compareAndSet(pending, -1)) {
                signalCapacity();
                // Deferred even on the event loop: closing inline would run close listeners
                // inside the caller's write.
                executeLater(
                        () -> {
                            ChannelHandlerUtils.markCloseFailure(
                                    channel, new IOException("SSE pending write limit exceeded"));
                            channel.close();
                        },
                        "overflow");
                return false;
            }
        }
        return false;
    }

    /**
     * Whether the caller is a Netty I/O thread, which must never park: this channel's loop, or
     * another client's, such as an async tool continuing on its HTTP client's callback.
     */
    private boolean onIoThread() {
        return channel.eventLoop().inEventLoop() || FastThreadLocalThread.currentThreadHasFastThreadLocal();
    }

    /**
     * Parks the producer until {@code pendingBytes} moves off {@code observed} (a write completed,
     * or the stream closed) or the stream closes. {@code false} when interrupted.
     */
    private boolean awaitCapacity(long observed) {
        capacityWaiters.incrementAndGet();
        capacityLock.lock();
        try {
            while (pendingBytes.get() == observed && channel.isActive() && !state.closed) {
                capacityFreed.await();
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            capacityLock.unlock();
            capacityWaiters.decrementAndGet();
        }
    }

    private void signalCapacity() {
        if (capacityWaiters.get() == 0) return;
        capacityLock.lock();
        try {
            capacityFreed.signalAll();
        } finally {
            capacityLock.unlock();
        }
    }

    /**
     * Measures {@code event}, reserves its size, then encodes it: a producer parked for capacity
     * holds no buffer, and a refused write never allocates one.
     */
    private void writeMeasured(SseEvent event, Admission admission) {
        final var measured = SseSerializer.measure(event);
        final long bytes = SseSerializer.measuredLength(measured) + TASK_OVERHEAD_BYTES;
        if (!reserve(bytes, admission)) return;
        final ByteBuf buf;
        try {
            buf = SseSerializer.encode(channel.alloc(), event, measured);
        } catch (Throwable t) {
            releaseCapacity(bytes);
            throw t;
        }
        dispatch(buf, bytes, false, null);
    }

    /** Allocates a buffer for a reserved write, returning the reservation if allocation fails. */
    private ByteBuf allocateReserved(int length, long bytes) {
        try {
            return channel.alloc().buffer(length);
        } catch (Throwable t) {
            releaseCapacity(bytes);
            throw t;
        }
    }

    /** Hands a reserved, encoded write to the event loop; its completion releases {@code bytes}. */
    private void dispatch(ByteBuf buf, long bytes, boolean startsStream, @Nullable Runnable onDropped) {
        final var promise = channel.newPromise();
        promise.addListener(f -> {
            releaseCapacity(bytes);
            if (!f.isSuccess() && onDropped != null) onDropped.run();
        });
        try {
            runOnEventLoop(() -> doWrite(buf, promise, startsStream));
        } catch (RejectedExecutionException e) {
            buf.release();
            promise.tryFailure(e);
        }
    }

    private void releaseCapacity(long bytes) {
        pendingBytes.updateAndGet(pending -> pending < 0 ? -1 : pending - bytes);
        signalCapacity();
    }

    private void discardQueued() {
        pendingBytes.set(-1);
        signalCapacity();
        final var cause = ChannelHandlerUtils.closeFailure(channel);
        final var failure = cause != null ? cause : new ClosedChannelException();
        if (!state.opened) startCompletion.completeExceptionally(failure);
        PendingWrite pending;
        while ((pending = queued.poll()) != null) {
            pending.buf().release();
            pending.promise().tryFailure(failure);
        }
    }

    /* ------- methods below run on the channel's EventLoop only ------- */

    /**
     * Opens the stream and flushes its initial write — the queued events (e.g. subscriptions/listen's
     * ack) if any, otherwise a priming event. The start future completes once that flush finishes,
     * so a caller can observe when the ack actually reached the transport rather than just when
     * {@code start()} returned.
     */
    private void doStart() {
        if (state.opened) return;
        if (!state.closed && !channel.isActive()) {
            state = State.CLOSED_UNOPENED;
        }
        if (state.closed) {
            startCompletion.completeExceptionally(new ClosedChannelException());
            return;
        }
        if (pendingBytes.get() < 0) return;
        state = State.OPEN;
        final var writes = new PromiseCombiner(channel.eventLoop());
        final var initialWrite = channel.newPromise();
        initialWrite.addListener(f -> {
            if (f.isSuccess()) startCompletion.complete(null);
            else startCompletion.completeExceptionally(f.cause());
        });

        var response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        HttpHelpers.setSseStreamHeaders(response, cors);
        writes.add(channel.write(response).addListener(writeFailureListener));
        SseHeartbeat.enable(channel, heartbeatInterval);
        if (queued.isEmpty()) {
            // Priming event: gives the client a Last-Event-ID baseline for reconnection (SEP-1699).
            // Carries this stream's key so a resume from the priming id replays only this stream.
            // Skipped when an event is already queued (e.g. subscriptions/listen's ack, which SEP-2575
            // requires to be the stream's first message) — that queued event is itself a valid baseline.
            var priming = new SseEvent(ServerEngine.wireEventId(streamKeyId, streamKey), "message", "");
            writes.add(channel.write(new DefaultHttpContent(SseSerializer.encode(channel.alloc(), priming)))
                    .addListener(writeFailureListener));
            logger.trace("POST-SSE stream started, priming event id={}, channel={}", streamKeyId, channel.id());
        } else {
            PendingWrite pending;
            while ((pending = queued.poll()) != null) {
                writes.add(channel.write(new DefaultHttpContent(pending.buf()), pending.promise())
                        .addListener(writeFailureListener));
            }
        }
        writes.finish(initialWrite);
        channel.flush();
    }

    private void doWrite(ByteBuf buf, ChannelPromise promise, boolean startsStream) {
        if (startsStream) doStart();
        if (pendingBytes.get() < 0 || state.closed || !channel.isActive() || (startsStream && !state.opened)) {
            buf.release();
            promise.tryFailure(new ClosedChannelException());
            return;
        }
        if (state == State.NEW) {
            queued.add(new PendingWrite(buf, promise));
            return;
        }
        channel.writeAndFlush(new DefaultHttpContent(buf), promise).addListener(writeFailureListener);
    }

    private ChannelFuture doClose(boolean reconnect) {
        if (state.closed) return closeWrite != null ? closeWrite : channel.newSucceededFuture();
        var wasOpen = state.opened;
        state = wasOpen ? State.CLOSED_OPENED : State.CLOSED_UNOPENED;
        discardQueued();
        if (!wasOpen) channel.closeFuture().removeListener(channelClosed);
        if (!channel.isActive() || !wasOpen) return channel.newSucceededFuture();
        // Before the terminating chunk: a heartbeat tick firing in the window between that chunk's
        // flush and the channel's close would write a comment the encoder no longer expects.
        SseHeartbeat.cancel(channel);
        if (reconnect) {
            channel.write(new DefaultHttpContent(
                            ByteBufUtil.writeUtf8(channel.alloc(), "retry: " + SSE_RETRY_DELAY_MS + "\n")))
                    .addListener(writeFailureListener);
        }
        closeWrite = channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
                .addListener(writeFailureListener)
                .addListener(ChannelFutureListener.CLOSE);
        return closeWrite;
    }

    /**
     * Records a failed write as the channel's close cause before closing it, so {@link #onClose}
     * reports a genuine transport failure instead of an ordinary disconnect.
     */
    private void closeOnWriteFailure(ChannelFuture f) {
        if (f.isSuccess() || ChannelHandlerUtils.closeFailure(channel) != null) return;
        logger.warn(
                "POST-SSE write failed, closing channel={}: {}",
                channel.id(),
                f.cause().getMessage());
        ChannelHandlerUtils.markCloseFailure(channel, f.cause());
        channel.close();
    }

    private static String abbreviate(String s) {
        return s != null && s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }
}
