/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.runtime;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.server.domain.LoggingLevel;
import dev.tachyonmcp.api.server.security.SecurityContext;
import dev.tachyonmcp.core.protocol.Protocol;
import dev.tachyonmcp.core.protocol.Protocols;
import dev.tachyonmcp.core.server.session.SessionKey;
import dev.tachyonmcp.core.server.session.SessionSnapshot;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * A streamable-HTTP session shared by protocols on this transport. Tracks lifecycle state and
 * the SSE channel (connection, backpressure, replay cursor) for a single client identified by
 * a unique string ID
 */
@InternalApi(since = "1.0.0-beta.26")
public class Session {

    private final String id;
    private final SessionKey key;
    private final Consumer<Session> onChange;
    private final Consumer<Session> onTouch;
    protected final AtomicReference<SessionState> state;
    protected volatile long lastActivityNanos;

    private final AtomicReference<SseConnection> connection;
    private final AtomicReference<Backpressure> backpressure;
    private final AtomicLong cursor;
    private final Set<String> enabledExtensions = ConcurrentHashMap.newKeySet();
    private final AtomicReference<@Nullable Protocol> protocol = new AtomicReference<>();
    private final AtomicReference<@Nullable LoggingLevel> loggingLevel = new AtomicReference<>();
    private final AtomicReference<@Nullable SecurityContext> securityContext = new AtomicReference<>();
    private final AtomicReference<SessionSnapshot> persistedSnapshot;
    private volatile @Nullable String resumingStreamKey;

    public Session(String id, SseConnection connection) {
        this(
                new SessionSnapshot(
                        new SessionKey(id, "local"), SessionState.INITIALIZING, null, Set.of(), null, Instant.MAX, 0),
                connection,
                ignored -> {},
                ignored -> {});
    }

    private Session(
            SessionSnapshot snapshot, SseConnection connection, Consumer<Session> onChange, Consumer<Session> onTouch) {
        this.id = snapshot.key().sessionId();
        this.key = snapshot.key();
        this.onChange = Objects.requireNonNull(onChange, "onChange");
        this.onTouch = Objects.requireNonNull(onTouch, "onTouch");
        this.state = new AtomicReference<>(snapshot.state());
        this.lastActivityNanos = System.nanoTime();
        this.connection = new AtomicReference<>(Objects.requireNonNull(connection, "connection"));
        this.backpressure = new AtomicReference<>(Backpressure.HOT);
        this.cursor = new AtomicLong(-1);
        this.enabledExtensions.addAll(snapshot.enabledExtensionIds());
        this.loggingLevel.set(snapshot.loggingLevel());
        this.persistedSnapshot = new AtomicReference<>(snapshot);
        final var protocolVersion = snapshot.protocolVersion();
        if (protocolVersion != null) {
            final var restoredProtocol = Protocols.list().stream()
                    .filter(candidate -> candidate.versionString().equals(snapshot.protocolVersion()))
                    .findFirst()
                    .orElseThrow(() ->
                            new IllegalArgumentException("Unsupported session protocol version: " + protocolVersion));
            protocol.set(restoredProtocol);
        }
    }

    /** Reconstructs process-local runtime state from a transport-free snapshot. */
    public static Session fromSnapshot(
            SessionSnapshot snapshot, SseConnection connection, Consumer<Session> onChange, Consumer<Session> onTouch) {
        return new Session(snapshot, connection, onChange, onTouch);
    }

    /** Returns the unique session identifier. */
    public String id() {
        return id;
    }

    /** Returns the persisted generation key. */
    public SessionKey key() {
        return key;
    }

    /** Returns the current session state. */
    public SessionState state() {
        return state.get();
    }

    /** Returns the nanosecond timestamp of the last activity on this session. */
    public long lastActivityNanos() {
        return lastActivityNanos;
    }

    /** Updates the last-activity timestamp to now. */
    public void touch() {
        this.lastActivityNanos = System.nanoTime();
        onTouch.accept(this);
    }

    /** Transitions from {@link SessionState#INITIALIZING} to {@link SessionState#ACTIVE}. */
    public boolean activate() {
        if (state.compareAndSet(SessionState.INITIALIZING, SessionState.ACTIVE)) {
            this.lastActivityNanos = System.nanoTime();
            onChange.accept(this);
            return true;
        }
        return false;
    }

    /** Returns the current SSE connection. */
    public SseConnection connection() {
        return connection.get();
    }

    /**
     * The POST-SSE stream key the current connection is resuming, parsed from a
     * {@code <n>#<key>} {@code Last-Event-ID}; {@code null} for the general GET listening stream or
     * a fresh connection. Lets a late POST-SSE response be re-delivered live to a connection that
     * explicitly resumed that stream, without crossing streams.
     */
    public @Nullable String resumingStreamKey() {
        return resumingStreamKey;
    }

    /** Records the POST-SSE stream key the current connection is resuming (or {@code null}). */
    public void resumingStreamKey(@Nullable String streamKey) {
        this.resumingStreamKey = streamKey;
    }

    /**
     * Replaces the SSE connection (e.g. after reconnection). Closes the previously attached
     * connection so a superseded channel is not left open (and kept alive by SSE heartbeats).
     * If the session is (or becomes) {@link SessionState#CLOSED}, the incoming connection is
     * closed instead of attached.
     */
    public void connection(SseConnection connection) {
        Objects.requireNonNull(connection, "connection");
        if (state.get() == SessionState.CLOSED) {
            connection.close();
            return;
        }
        var previous = this.connection.getAndSet(connection);
        this.lastActivityNanos = System.nanoTime();
        if (previous != connection && previous != SseConnection.noop()) {
            previous.close();
        }
        // A concurrent close() may have transitioned to CLOSED after the guard above; if so, detach
        // and close the connection we just attached so a CLOSED session never holds a live channel.
        if (state.get() == SessionState.CLOSED && this.connection.compareAndSet(connection, SseConnection.noop())) {
            connection.close();
        }
    }

    /**
     * Detaches {@code expected} if it is still the current connection, resetting to
     * {@link SseConnection#noop()}. Returns {@code true} if it was detached. Used by a channel's
     * close listener so a superseded connection's close does not wipe a newer one.
     */
    public boolean clearConnection(SseConnection expected) {
        return connection.compareAndSet(expected, SseConnection.noop());
    }

    /** Returns the current backpressure state. */
    public Backpressure backpressure() {
        return backpressure.get();
    }

    /** Returns the last replayed SSE event cursor. */
    public long cursor() {
        return cursor.get();
    }

    /** Sets the replayed SSE event cursor. */
    public void cursor(long position) {
        cursor.set(position);
    }

    /** Returns the protocol negotiated when this session was initialized, if available. */
    public @Nullable Protocol protocol() {
        return protocol.get();
    }

    /** Records the protocol negotiated when this session was initialized. */
    public void protocol(Protocol protocol) {
        if (this.protocol.compareAndSet(null, Objects.requireNonNull(protocol, "protocol"))) {
            onChange.accept(this);
        }
    }

    /**
     * Returns the security context of the request that created this session. It identifies the
     * session's owner, never the current caller: requests are authenticated on their own, and the
     * caller's context is {@link dev.tachyonmcp.api.runtime.InteractionContext#securityContext()}.
     * Process-local; a session restored from a snapshot is anonymous.
     */
    public SecurityContext securityContext() {
        final var owner = securityContext.get();
        return owner != null ? owner : SecurityContext.anonymous();
    }

    /**
     * Records the security context of the request that created this session, once.
     *
     * @throws IllegalStateException if a context was already recorded
     */
    public void securityContext(SecurityContext owner) {
        if (!securityContext.compareAndSet(null, Objects.requireNonNull(owner, "owner"))) {
            throw new IllegalStateException("Session " + id + " already has a security context");
        }
    }

    /** Enables an extension for this session. */
    public void enableExtension(String extensionId) {
        if (enabledExtensions.add(extensionId)) {
            onChange.accept(this);
        }
    }

    /** Returns the enabled extension identifiers. */
    public Set<String> enabledExtensionIds() {
        return Set.copyOf(enabledExtensions);
    }

    /** Returns whether the given extension is enabled for this session. */
    public boolean isExtensionEnabled(String extensionId) {
        return enabledExtensions.contains(extensionId);
    }

    /** Returns the client-selected logging threshold, if configured. */
    public @Nullable LoggingLevel loggingLevel() {
        return loggingLevel.get();
    }

    /** Records the client-selected logging threshold. */
    public void loggingLevel(LoggingLevel level) {
        var next = Objects.requireNonNull(level, "level");
        if (loggingLevel.getAndSet(next) != next) {
            onChange.accept(this);
        }
    }

    /** Captures transport-free state for durable storage. */
    public SessionSnapshot snapshot(Instant expiresAt, long revision) {
        var negotiatedProtocol = protocol.get();
        return new SessionSnapshot(
                key,
                state(),
                negotiatedProtocol != null ? negotiatedProtocol.versionString() : null,
                enabledExtensionIds(),
                loggingLevel(),
                expiresAt,
                revision);
    }

    /** Returns the last snapshot persisted by this runtime. */
    public SessionSnapshot persistedSnapshot() {
        return persistedSnapshot.get();
    }

    /** Records the last snapshot persisted by this runtime. */
    public void persistedSnapshot(SessionSnapshot snapshot) {
        persistedSnapshot.set(Objects.requireNonNull(snapshot, "snapshot"));
    }

    /** Recomputes and returns the backpressure state based on stream writability. */
    public Backpressure computeBackpressure() {
        return backpressure.updateAndGet(current -> {
            if (!connection.get().isWritable()) {
                return Backpressure.COLD;
            }
            return Backpressure.HOT;
        });
    }

    /** Returns {@code true} if the session should throttle outbound events. */
    public boolean shouldThrottle() {
        return computeBackpressure() != Backpressure.HOT;
    }

    /**
     * Sends an SSE event to the client. Returns {@code false} without sending when no connection
     * is attached or the stream is throttled ({@link Backpressure#COLD}) — a slow client must not
     * accumulate events in the channel's outbound buffer. Dropped events stay in the event log and
     * are replayable via {@code Last-Event-ID} on reconnect.
     */
    public boolean send(SseEvent event) {
        var conn = connection.get();
        if (conn == SseConnection.noop()) {
            return false;
        }
        if (shouldThrottle()) {
            return false;
        }
        conn.send(event);
        return true;
    }

    /** Closes the session and its underlying connection. Returns {@code true} if this call closed it. */
    public boolean close() {
        var closed = state.compareAndSet(SessionState.ACTIVE, SessionState.CLOSED)
                || state.compareAndSet(SessionState.DRAINING, SessionState.CLOSED)
                || state.compareAndSet(SessionState.INITIALIZING, SessionState.CLOSED);
        if (closed) {
            var conn = connection.getAndSet(SseConnection.noop());
            conn.close();
            onChange.accept(this);
        }
        return closed;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj == null || getClass() != obj.getClass()) return false;
        var that = (Session) obj;
        return Objects.equals(this.id, that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Session[id=" + id + ", state=" + state.get() + ", bp=" + backpressure.get() + "]";
    }
}
