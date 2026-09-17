/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.session;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.core.runtime.SessionState;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default process-local session snapshot store.
 *
 * <p>Every mutation is a lock-free read-then-swap on the current value: the replacement snapshot is
 * built outside the map's bin monitor, so a contended session never pins a virtual thread while
 * allocating, and a lost race re-reads and retries instead of blocking.
 */
@InternalApi
public final class InMemorySessionStore implements SessionStore {

    private final ConcurrentHashMap<String, SessionSnapshot> snapshots = new ConcurrentHashMap<>();

    @Override
    public SessionSnapshot create(SessionKey key, Instant expiresAt) {
        final var snapshot = new SessionSnapshot(key, SessionState.INITIALIZING, null, Set.of(), null, expiresAt, 0);
        snapshots.put(key.sessionId(), snapshot);
        return snapshot;
    }

    @Override
    public Optional<SessionSnapshot> find(String sessionId) {
        return Optional.ofNullable(snapshots.get(sessionId));
    }

    @Override
    public boolean compareAndSet(SessionSnapshot expected, SessionSnapshot updated) {
        if (!expected.key().equals(updated.key()) || updated.revision() <= expected.revision()) {
            return false;
        }
        return snapshots.replace(expected.key().sessionId(), expected, updated);
    }

    @Override
    public boolean touch(SessionKey key, Instant expiresAt) {
        final var sessionId = key.sessionId();
        while (true) {
            final var current = snapshots.get(sessionId);
            if (current == null || !current.key().equals(key)) {
                return false;
            }
            if (snapshots.replace(sessionId, current, touched(current, expiresAt))) {
                return true;
            }
        }
    }

    @Override
    public boolean terminate(SessionKey key) {
        final var sessionId = key.sessionId();
        while (true) {
            final var current = snapshots.get(sessionId);
            if (current == null || !current.key().equals(key)) {
                return false;
            }
            if (snapshots.remove(sessionId, current)) {
                return true;
            }
        }
    }

    @Override
    public void close() {
        snapshots.clear();
    }

    private static SessionSnapshot touched(SessionSnapshot current, Instant expiresAt) {
        return new SessionSnapshot(
                current.key(),
                current.state(),
                current.protocolVersion(),
                current.enabledExtensionIds(),
                current.loggingLevel(),
                expiresAt,
                current.revision() + 1);
    }
}
