/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.internal;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.server.domain.LoggingLevel;
import dev.tachyonmcp.api.server.domain.ProgressToken;
import dev.tachyonmcp.api.server.domain.RequestId;
import dev.tachyonmcp.api.server.domain.ServerCapabilities;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.api.server.session.SessionIdGenerator;
import dev.tachyonmcp.core.protocol.ProtocolResponseMapper;
import dev.tachyonmcp.core.runtime.Session;
import dev.tachyonmcp.core.runtime.SseEvent;
import dev.tachyonmcp.core.server.OutboundSseStream;
import dev.tachyonmcp.core.server.RpcMethodHandler;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.server.features.tasks.TaskRegistry;
import dev.tachyonmcp.core.server.session.SessionEvent;
import dev.tachyonmcp.core.transport.jsonrpc.JsonRpcCodec;
import io.netty.handler.codec.http.HttpRequest;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import org.jspecify.annotations.Nullable;

/**
 * Engine SPI for MCP server internals. Extends the user-facing {@link TachyonServer} with the full
 * set of methods needed by transport, dispatch, registries, and session management.
 *
 * <p><strong>Not a stability contract.</strong> This interface lives in an {@code internal}
 * package for a reason: it may change without notice across minor versions. Only code that ships
 * inside the {@code tachyon-core} jar — transport handlers, dispatch plumbing, registry
 * implementations — should depend on it. External code that reaches for {@code ServerEngine} is
 * opting into a breakage-prone dependency.
 */
// ponytail: internal pkg not JPMS-sealed; module split later if a user pins ServerEngine.
@InternalApi
public interface ServerEngine extends TachyonServer {

    /** Returns the protocol response mapper for the default MCP version. */
    ProtocolResponseMapper responseMapper();

    /** Returns {@code true} when running in stateless mode (no session persistence). */
    boolean isStateless();

    /** Returns the configured session id generator. */
    @Nullable
    SessionIdGenerator<? super HttpRequest> sessionIdGenerator();

    /** Sets the logging level for a session. */
    void setLoggingLevel(String sessionId, LoggingLevel level);

    /** Returns the logging level for a session, or {@code null} if not set. */
    @Nullable
    LoggingLevel getLoggingLevel(String sessionId);

    /** Resolves effective capabilities based on configuration and registered features. */
    ServerCapabilities resolveCapabilities();

    /** Sends a notification to all active sessions. */
    void broadcastNotification(String method, Object params);

    /** Registers a method handler keyed by its own method name. */
    void registerHandler(RpcMethodHandler<?, ?> handler);

    /** Registers a method handler with an explicit method name. */
    void registerHandler(String method, RpcMethodHandler<?, ?> handler);

    /** Returns the handler for a method, or {@code null} if not registered. */
    @Nullable
    RpcMethodHandler<?, ?> getHandler(String method);

    /** Returns the extension ID that owns the given method, or {@code null} if none. */
    @Nullable
    String extensionForMethod(String method);

    /** Returns {@code true} if the given extension's methods may be dispatched without client declaration. */
    boolean extensionNegotiationOptional(String extensionId);

    /** Creates and registers a new session with the given ID. */
    Session createSession(String sessionId);

    /** Returns the session with the given ID, if present. */
    Optional<Session> getSession(String sessionId);

    /** Returns only a process-local session, without consulting persistent storage. */
    Optional<Session> getLocalSession(String sessionId);

    /** Removes and closes the session with the given ID. */
    void removeSession(String sessionId);

    /** Sends a notification to the given session. */
    void sendNotification(Session session, String method, Object params);

    /** Sends a notification to the given session, optionally via a bound outbound SSE stream. */
    void sendNotification(Session session, String method, @Nullable Object params, @Nullable OutboundSseStream stream);

    /** Returns the executor used for handler dispatch. */
    ExecutorService executor();

    /** Returns the server-wide request lifecycle tracker. */
    OperationTracker operations();

    TaskRegistry tasksRegistry();

    /**
     * Maps and sends a task status notification to the owning session, if any, and to
     * {@code subscriptions/listen} subscribers of the task. A {@code null} session is never
     * broadcast. Never blocks on a slow client: its stream is closed instead.
     */
    void notifyTaskStatus(TaskSnapshot snapshot, @Nullable String sessionId);

    /**
     * Maps and sends a task progress notification to the session that owns {@code progressToken};
     * dropped when {@code sessionId} is {@code null}.
     */
    void notifyTaskProgress(
            ProgressToken progressToken,
            @Nullable String sessionId,
            double progress,
            @Nullable Double total,
            @Nullable String message);

    /** Pushes {@code notifications/resources/updated} to every stateless {@code subscriptions/listen} stream subscribed to {@code uri}. */
    void notifyResourceSubscriptions(String uri);

    /** Sends a request to the client and returns a future that completes with the response. */
    CompletableFuture<String> sendRequest(Session session, String method, Object params);

    /** Sends a request to the client, optionally via a bound outbound SSE stream, and returns a future. */
    CompletableFuture<String> sendRequest(
            Session session, String method, Object params, @Nullable OutboundSseStream stream);

    /**
     * Completes a pending server-to-client request when {@code sessionId} owns it in stateful mode
     * or {@code channelId} owns it in stateless mode. Missing or mismatched ownership is ignored.
     *
     * @return {@code true} when the matching pending request was completed
     */
    boolean completePendingRequest(
            @Nullable RequestId requestId, @Nullable String sessionId, @Nullable String channelId, String resultJson);

    /**
     * Fails a pending server-to-client request when {@code sessionId} owns it in stateful mode or
     * {@code channelId} owns it in stateless mode. Missing or mismatched ownership is ignored.
     *
     * @return {@code true} when the matching pending request was failed
     */
    boolean failPendingRequest(
            @Nullable RequestId requestId, @Nullable String sessionId, @Nullable String channelId, String message);

    /** Registers a pending request with its stateful session or stateless transport owner. */
    void registerPendingRequest(
            RequestId requestId,
            @Nullable String sessionId,
            @Nullable OutboundSseStream stream,
            CompletableFuture<String> future);

    /** Appends an event to the session log. */
    void appendEvent(SessionEvent event);

    /** Replays session events after the given sequence number. */
    List<SessionEvent> replay(String sessionId, long lastSeq);

    /** Returns and increments the event ID counter. */
    long nextEventId();

    // ──────────────────────────────────────────────────────────────
    // Static helpers
    // ──────────────────────────────────────────────────────────────

    /** Formats an SSE wire event id: the global counter value, suffixed with {@code #<streamKey>}. */
    static String wireEventId(long sseEventId, @Nullable String streamKey) {
        return streamKey == null ? String.valueOf(sseEventId) : sseEventId + "#" + streamKey;
    }

    /** Converts a session event to an SSE event, or returns {@code null} for non-transport events. */
    static @Nullable SseEvent toSseEvent(SessionEvent event) {
        return switch (event) {
            case SessionEvent.ResponseEvent r ->
                new SseEvent(wireEventId(r.sseEventId(), r.streamKey()), "message", r.resultJson());
            case SessionEvent.NotificationEvent n -> {
                var json = JsonRpcCodec.serializeNotificationAsString(n.method(), n.paramsJson());
                yield new SseEvent(wireEventId(n.sseEventId(), n.streamKey()), "message", json);
            }
            case SessionEvent.OutboundRequestEvent o -> {
                var json = JsonRpcCodec.serializeRequestAsString(o.requestId(), o.method(), o.paramsJson());
                yield new SseEvent(wireEventId(o.sseEventId(), o.streamKey()), "message", json);
            }
            case SessionEvent.RequestEvent ignored -> null;
            case SessionEvent.CancelEvent ignored -> null;
        };
    }
}
