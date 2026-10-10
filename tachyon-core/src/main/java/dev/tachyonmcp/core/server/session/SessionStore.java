/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.session;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.core.runtime.SessionState;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * Persistence boundary for immutable, transport-free MCP session snapshots.
 *
 * <p>Methods execute synchronously and may perform I/O. Tachyon invokes them outside transport
 * event-loop threads. Implementations must be thread-safe.
 *
 * <p>A {@link RuntimeException} from {@link #terminate} never keeps the local session alive: Tachyon
 * closes its connection and in-flight waiters anyway. Explicit removal (HTTP {@code DELETE}) then
 * answers {@code 500}; idle expiry logs the failure and moves on. The snapshot stays restorable on
 * any node until its {@link SessionSnapshot#expiresAt()}. That expiry is an idle TTL, not an
 * absolute revocation deadline: activity on a node that restores the snapshot extends it through
 * {@link #touch}.
 *
 * <p>Every write within a generation increments {@link SessionSnapshot#revision()}, so a
 * {@link SessionKey} and a revision identify one snapshot version. Implementations must keep that
 * invariant: conditional operations rely on it instead of comparing whole snapshots.
 */
@ExperimentalApi(since = "1.0.0-beta.26")
public interface SessionStore extends AutoCloseable {

    /** Creates a new generation, atomically replacing the current generation for its session ID. */
    SessionSnapshot create(SessionKey key, Instant expiresAt);

    /** Finds the current snapshot for a session ID. */
    Optional<SessionSnapshot> find(String sessionId);

    /** Replaces the current snapshot when it exactly equals {@code expected}. */
    boolean compareAndSet(SessionSnapshot expected, SessionSnapshot updated);

    /**
     * Extends expiry and increments the revision once when the current snapshot still belongs to
     * {@code key}.
     */
    boolean touch(SessionKey key, Instant expiresAt);

    /** Removes the current snapshot when its generation still equals {@code key}. */
    boolean terminate(SessionKey key);

    /**
     * Removes the current snapshot only while its generation equals {@code key} and its revision
     * equals {@code revision}, so a write from another node after that revision was read keeps the
     * snapshot.
     *
     * <p>The default checks through {@link #find} and then removes through
     * {@link #terminate(SessionKey)}, so a write landing between the two is lost. Override it with
     * one atomic operation, such as a conditional delete on the key and revision.
     *
     * @param key      the generation read from {@link #find}
     * @param revision the revision read from {@link #find}
     * @return {@code true} if the snapshot was removed
     */
    @ExperimentalApi(since = "1.1.0")
    default boolean terminate(SessionKey key, long revision) {
        return find(key.sessionId())
                        .filter(current -> current.key().equals(key) && current.revision() == revision)
                        .isPresent()
                && terminate(key);
    }

    /**
     * Returns the store used when sessions are disabled: it persists nothing, so no snapshot can be
     * hydrated from it.
     *
     * @return the shared no-op store, never {@code null}
     */
    static SessionStore noop() {
        return NoopSessionStore.INSTANCE;
    }
}

/**
 * Snapshot store for a stateless server: sessions live only in {@link SessionManager}'s process-local
 * map, so nothing is written and {@link #find} never resolves.
 *
 * <p>{@code compareAndSet}, {@code touch} and {@code terminate(SessionKey)} answer {@code true} —
 * "accepted, nothing to persist". Answering {@code false} would make {@code SessionManager#persist}
 * read the write as lost ownership and evict the local session on its first state change.
 */
final class NoopSessionStore implements SessionStore {

    static final SessionStore INSTANCE = new NoopSessionStore();

    private NoopSessionStore() {}

    @Override
    public SessionSnapshot create(SessionKey key, Instant expiresAt) {
        return new SessionSnapshot(key, SessionState.INITIALIZING, null, Set.of(), null, expiresAt, 0);
    }

    @Override
    public Optional<SessionSnapshot> find(String sessionId) {
        return Optional.empty();
    }

    @Override
    public boolean compareAndSet(SessionSnapshot expected, SessionSnapshot updated) {
        return true;
    }

    @Override
    public boolean touch(SessionKey key, Instant expiresAt) {
        return true;
    }

    @Override
    public boolean terminate(SessionKey key) {
        return true;
    }

    @Override
    public void close() {}
}
