/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.session;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.api.server.domain.RequestId;
import org.jspecify.annotations.Nullable;

/** A recorded session event — request, response, notification, or cancellation. */
@ExperimentalApi(since = "1.0.0-beta.26")
public sealed interface SessionEvent {

    /** The session this event belongs to. */
    @Nullable
    String sessionId();

    /** Timestamp of the event (epoch millis). */
    long timestamp();

    /** SSE event ID (for replay), or -1 if not assigned. */
    default long sseEventId() {
        return -1;
    }

    /**
     * Identifies the SSE stream the event was delivered on: a POST-SSE stream's key, or
     * {@code null} for the session's general-purpose GET stream. Replay after reconnection is
     * per-stream (MCP Streamable HTTP: the server MUST NOT replay messages that would have been
     * sent on a different stream), so each outbound event records its originating stream.
     */
    default @Nullable String streamKey() {
        return null;
    }

    /**
     * An inbound request from the client.
     *
     * @param sessionId  the session this event belongs to
     * @param requestId  the request identifier
     * @param method     the method name
     * @param paramsJson the JSON-encoded parameters, or {@code null}
     * @param timestamp  the event timestamp (epoch millis)
     */
    record RequestEvent(
            @Nullable String sessionId,
            RequestId requestId,
            String method,
            @Nullable String paramsJson,
            long timestamp) implements SessionEvent {}

    /**
     * An outbound (server-to-client) request.
     *
     * @param sessionId  the session this event belongs to
     * @param requestId  the request identifier
     * @param method     the method name
     * @param paramsJson the JSON-encoded parameters, or {@code null}
     * @param timestamp  the event timestamp (epoch millis)
     * @param sseEventId the SSE event id for replay
     * @param streamKey  the SSE stream key, or {@code null} for the general-purpose stream
     */
    record OutboundRequestEvent(
            @Nullable String sessionId,
            RequestId requestId,
            String method,
            @Nullable String paramsJson,
            long timestamp,
            long sseEventId,
            @Nullable String streamKey)
            implements SessionEvent {}

    /**
     * A response sent to the client.
     *
     * @param sessionId  the session this event belongs to
     * @param requestId  the request identifier
     * @param resultJson the JSON-encoded result payload
     * @param timestamp  the event timestamp (epoch millis)
     * @param sseEventId the SSE event id for replay
     * @param streamKey  the SSE stream key, or {@code null} for the general-purpose stream
     */
    record ResponseEvent(
            @Nullable String sessionId,
            RequestId requestId,
            String resultJson,
            long timestamp,
            long sseEventId,
            @Nullable String streamKey)
            implements SessionEvent {}

    /**
     * A cancellation request.
     *
     * @param sessionId the session this event belongs to
     * @param requestId the request identifier being cancelled
     * @param timestamp the event timestamp (epoch millis)
     */
    record CancelEvent(String sessionId, RequestId requestId, long timestamp) implements SessionEvent {}

    /**
     * A notification sent to or received from the client.
     *
     * @param sessionId  the session this event belongs to
     * @param method     the notification method name
     * @param paramsJson the JSON-encoded notification parameters, or {@code null}
     * @param timestamp  the event timestamp (epoch millis)
     * @param sseEventId the SSE event id for replay
     * @param streamKey  the SSE stream key, or {@code null} for the general-purpose stream
     */
    record NotificationEvent(
            @Nullable String sessionId,
            String method,
            @Nullable String paramsJson,
            long timestamp,
            long sseEventId,
            @Nullable String streamKey)
            implements SessionEvent {}
}
