/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.core.runtime.SseEvent;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Outbound SSE stream bound to the current dispatch context. Allows a handler executing a
 * JSON-RPC request to lazily upgrade the response transport to a Server-Sent Events stream so
 * the server can push notifications and requests to the client before the final response.
 *
 * <p>The stream is dormant until {@link #start()} is called (typically on the first server-to-client
 * message issued during dispatch). Once started, subsequent events flow as SSE frames. The final
 * response is appended as an SSE event by the dispatcher after the handler completes, then the
 * stream is closed.
 *
 * <p>Implementations are transport-specific. The Netty implementation ({@code PostSseStream}) ties
 * this to the HTTP POST response channel; other transports may bind it differently.
 */
@InternalApi
public interface OutboundSseStream {

    /**
     * Returns the transport-channel identity backing this stream, or {@code null} when the
     * transport has no channel identity.
     */
    default @Nullable String channelId() {
        return null;
    }

    /**
     * A stable key identifying this stream within its session, used to tag event-log entries and
     * to suffix SSE event ids ({@code <n>#<key>}) so a {@code Last-Event-ID} can be correlated
     * back to the originating stream for per-stream replay. {@code null} identifies the session's
     * general-purpose GET stream.
     */
    default @Nullable String streamKey() {
        return null;
    }

    /**
     * Activates the SSE stream and emits initial framing, including any events queued before this
     * call (e.g. {@code subscriptions/listen}'s ack, which must be queued ahead of {@code start()}
     * so it lands first). Idempotent — subsequent calls are no-ops. May be called from any thread.
     *
     * @return a stage that completes once that initial write — ack included — has been flushed to
     *     the transport, or completes exceptionally if the write failed, the channel was already
     *     inactive, or the stream was already closed; a second, no-op call mirrors the outcome of
     *     the call that actually opened the stream rather than completing ahead of its flush
     */
    CompletionStage<Void> start();

    /**
     * @return {@code true} once the stream has been opened.
     */
    boolean started();

    /**
     * Writes an SSE event. If {@link #start()} has not been called yet, the event is buffered
     * and emitted when the stream upgrades. May be called from any thread.
     *
     * @param event the SSE event to write
     */
    void writeEvent(@Nullable SseEvent event);

    /**
     * Writes an SSE comment line ({@code : message\r\n}), upgrading the stream via {@link #start()}
     * first if it has not started. A comment carries no event data — it exists to keep the
     * connection alive without a progress token. A {@code null} or blank message emits a bare
     * {@code :\r\n}. May be called from any thread. Default is a no-op for transports that do not
     * support raw comments.
     *
     * @param message comment text (a single line; embedded line breaks are flattened), or {@code null}
     */
    default void comment(@Nullable String message) {}

    /**
     * Closes the SSE stream. Idempotent — subsequent calls are no-ops.
     */
    void close();

    /**
     * Registers a callback invoked when the underlying transport connection closes, however that
     * happens — client disconnect, {@link #close()}, or a dead socket detected on write. Used by a
     * long-lived handler (e.g. {@code subscriptions/listen}) to clean up stream-scoped state it
     * cannot otherwise learn about, and to distinguish a genuine transport failure from an ordinary
     * close. Default is a no-op for transports with no close signal.
     *
     * @param callback invoked at most once, on an unspecified thread, with the failure cause when
     *                 the close was abnormal, or {@code null} for an ordinary close
     */
    default void onClose(Consumer<@Nullable Throwable> callback) {}
}
