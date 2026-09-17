/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty.sse;

import static dev.tachyonmcp.core.transport.netty.sse.SseManager.SSE_RETRY_DELAY_MS;

import dev.tachyonmcp.core.runtime.SseEvent;
import dev.tachyonmcp.core.server.OutboundSseStream;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.transport.netty.ChannelHandlerUtils;
import dev.tachyonmcp.core.transport.netty.http.HttpHelpers;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
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
 * {@link io.netty.channel.EventLoop}. The state is volatile only because {@link #started()} may
 * be queried from another thread; only the event loop writes it.
 */
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
    private final @Nullable String origin;
    private final LongSupplier eventIdSupplier;
    private final String streamKey;
    private final Duration heartbeatInterval;
    private final List<SseEvent> queued = new ArrayList<>();
    private volatile State state = State.NEW;
    private @Nullable ChannelFuture closeWrite;

    public PostSseStream(
            Channel channel, @Nullable String origin, LongSupplier eventIdSupplier, Duration heartbeatInterval) {
        this.channel = channel;
        this.origin = origin;
        this.eventIdSupplier = eventIdSupplier;
        this.heartbeatInterval = heartbeatInterval;
        // Session-unique key (one counter draw per POST) tagging this stream's events in the log
        // and suffixing its SSE ids, so Last-Event-ID resolves to THIS stream on replay. Not the
        // JSON-RPC request id — clients may reuse those across sequential requests.
        this.streamKey = String.valueOf(eventIdSupplier.getAsLong());
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
        var completion = new CompletableFuture<Void>();
        try {
            runOnEventLoop(() -> doStart(completion));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            completion.completeExceptionally(e);
        }
        return completion;
    }

    @Override
    public boolean started() {
        return state.opened;
    }

    @Override
    public void writeEvent(@Nullable SseEvent event) {
        if (event == null) return;
        runOnEventLoop(() -> doWriteEvent(event));
    }

    /**
     * Writes a final response event, invoking {@code onDropped} (on the event loop) when the write
     * is discarded because this stream is already closed or its channel is dead — letting the caller
     * re-deliver the buffered response to a reconnected stream instead of losing it.
     */
    public void writeEvent(long sseEventId, byte[] body, @Nullable Runnable onDropped) {
        try {
            runOnEventLoop(() -> doWriteEvent(sseEventId, body, onDropped));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            if (onDropped != null) onDropped.run();
        }
    }

    @Override
    public void comment(@Nullable String message) {
        // Self-starting: a comment upgrades the buffered POST to SSE even when no event was written
        // yet — this is the token-free keep-alive path. doStart is idempotent.
        runOnEventLoop(() -> {
            doStart(new CompletableFuture<>());
            doWriteComment(message);
        });
    }

    @Override
    public void close() {
        runOnEventLoop(() -> doClose(true));
    }

    public void terminate() {
        runOnEventLoop(() -> doClose(false));
    }

    /** Terminates the stream and completes after its final write succeeds or fails. */
    public ChannelFuture terminateAsync() {
        final var completion = channel.newPromise();
        // Fallback: a shutting-down event loop can accept the task below and never run it, which
        // would leave this promise — and the shutdown drain waiting on it — pending forever.
        channel.closeFuture().addListener(f -> completion.trySuccess());
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

    private void runOnEventLoop(Runnable task) {
        var eventLoop = channel.eventLoop();
        if (eventLoop.inEventLoop()) {
            task.run();
        } else {
            eventLoop.execute(task);
        }
    }

    /* ------- methods below run on the channel's EventLoop only ------- */

    /**
     * Opens the stream and flushes its initial write — the queued events (e.g. subscriptions/listen's
     * ack) if any, otherwise a priming event. {@code completion} completes once that flush finishes,
     * so a caller can observe when the ack actually reached the transport rather than just when
     * {@code start()} returned.
     */
    private void doStart(CompletableFuture<Void> completion) {
        if (state != State.NEW) {
            // Idempotent no-op: nothing new was flushed for this call, so there is nothing to wait on.
            completion.complete(null);
            return;
        }
        if (!channel.isActive()) {
            state = State.CLOSED_UNOPENED;
            completion.completeExceptionally(new ClosedChannelException());
            return;
        }
        state = State.OPEN;

        var response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        HttpHelpers.setSseStreamHeaders(response, origin);
        channel.write(response);
        SseHeartbeat.enable(channel, heartbeatInterval);
        ChannelFuture initialWrite;
        if (queued.isEmpty()) {
            // Priming event: gives the client a Last-Event-ID baseline for reconnection (SEP-1699).
            // Carries this stream's key so a resume from the priming id replays only this stream.
            // Skipped when an event is already queued (e.g. subscriptions/listen's ack, which SEP-2575
            // requires to be the stream's first message) — that queued event is itself a valid baseline.
            var primingId = eventIdSupplier.getAsLong();
            var priming = new SseEvent(ServerEngine.wireEventId(primingId, streamKey), "message", "");
            initialWrite = channel.write(new DefaultHttpContent(SseSerializer.encode(channel.alloc(), priming)));
            logger.trace("POST-SSE stream started, priming event id={}, channel={}", primingId, channel.id());
        } else {
            ChannelFuture lastQueuedWrite = null;
            for (var event : queued) {
                lastQueuedWrite = channel.write(new DefaultHttpContent(SseSerializer.encode(channel.alloc(), event)));
            }
            queued.clear();
            initialWrite = lastQueuedWrite;
        }
        channel.flush();
        initialWrite.addListener(f -> {
            if (f.isSuccess()) completion.complete(null);
            else completion.completeExceptionally(f.cause());
        });
    }

    private void doWriteEvent(SseEvent event) {
        if (state.closed || !channel.isActive()) {
            return;
        }
        if (state == State.NEW) {
            queued.add(event);
            logger.trace("POST-SSE queued event (not started), id={}, data={}", event.id(), abbreviate(event.data()));
            return;
        }
        logger.trace("POST-SSE writing event, id={}, data={}", event.id(), abbreviate(event.data()));
        var buf = SseSerializer.encode(channel.alloc(), event);
        channel.writeAndFlush(new DefaultHttpContent(buf)).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                logger.warn(
                        "POST-SSE write failed, closing channel={}: {}",
                        channel.id(),
                        f.cause().getMessage());
                ChannelHandlerUtils.markCloseFailure(channel, f.cause());
                channel.close();
            }
        });
    }

    private void doWriteEvent(long sseEventId, byte[] body, @Nullable Runnable onDropped) {
        if (state.closed || !channel.isActive()) {
            if (onDropped != null) onDropped.run();
            return;
        }
        if (state == State.NEW) {
            // Stream not yet upgraded — fall back to the String path; rare path, OK to decode here.
            queued.add(new SseEvent(
                    ServerEngine.wireEventId(sseEventId, streamKey),
                    "message",
                    new String(body, StandardCharsets.UTF_8)));
            return;
        }
        var buf = SseSerializer.encode(channel.alloc(), ServerEngine.wireEventId(sseEventId, streamKey), body);
        channel.writeAndFlush(new DefaultHttpContent(buf)).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                ChannelHandlerUtils.markCloseFailure(channel, f.cause());
                channel.close();
            }
        });
    }

    private void doWriteComment(@Nullable String message) {
        if (state != State.OPEN || !channel.isActive()) return;
        // SSE comment = a line starting with ':'. Flatten embedded line breaks so the message
        // cannot inject extra SSE lines/events. Blank/null → bare ':' heartbeat.
        String line = message == null || message.isBlank()
                ? ":\r\n"
                : ": " + message.replace('\r', ' ').replace('\n', ' ') + "\r\n";
        var buf = ByteBufUtil.writeUtf8(channel.alloc(), line);
        channel.writeAndFlush(new DefaultHttpContent(buf)).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                ChannelHandlerUtils.markCloseFailure(channel, f.cause());
                channel.close();
            }
        });
    }

    private ChannelFuture doClose(boolean reconnect) {
        if (state.closed) return closeWrite != null ? closeWrite : channel.newSucceededFuture();
        var wasOpen = state.opened;
        state = wasOpen ? State.CLOSED_OPENED : State.CLOSED_UNOPENED;
        if (!channel.isActive() || !wasOpen) return channel.newSucceededFuture();
        if (reconnect) {
            channel.write(new DefaultHttpContent(
                    ByteBufUtil.writeUtf8(channel.alloc(), "retry: " + SSE_RETRY_DELAY_MS + "\n")));
        }
        closeWrite = channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).addListener(ChannelFutureListener.CLOSE);
        return closeWrite;
    }

    private static String abbreviate(String s) {
        return s != null && s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }
}
