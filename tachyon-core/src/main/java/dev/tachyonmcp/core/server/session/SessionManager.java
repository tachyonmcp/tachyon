/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.session;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.core.runtime.Session;
import dev.tachyonmcp.core.runtime.SessionState;
import dev.tachyonmcp.core.runtime.SseConnection;
import dev.tachyonmcp.core.server.internal.AbstractJanitor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Coordinates process-local session runtimes with immutable persisted snapshots. */
@InternalApi
public final class SessionManager implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(SessionManager.class);
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LifecycleLock> lifecycleLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SessionKey, Boolean> pendingExpiryRefreshes = new ConcurrentHashMap<>();
    private final SessionStore store;
    private final Clock clock;
    private final Executor persistenceExecutor;
    private volatile Duration ttl;
    private volatile Duration expiryRefreshMargin;
    private @Nullable AbstractJanitor janitor;

    /** Creates a manager using the system clock and a five-minute snapshot TTL. */
    public SessionManager(SessionStore store) {
        this(store, Clock.systemUTC(), Duration.ofMinutes(5), Runnable::run);
    }

    /** Creates a manager with explicit time sources for deterministic lifecycle handling. */
    public SessionManager(SessionStore store, Clock clock, Duration ttl) {
        this(store, clock, ttl, Runnable::run);
    }

    /** Creates a manager that delegates expiry refreshes to the supplied executor. */
    public SessionManager(SessionStore store, Clock clock, Duration ttl, Executor persistenceExecutor) {
        this.store = store;
        this.clock = clock;
        this.ttl = ttl;
        this.expiryRefreshMargin = ttl.dividedBy(2);
        this.persistenceExecutor = persistenceExecutor;
    }

    /** Creates a session with no initial connection. */
    public Session createSession(String sessionId) {
        return createSession(sessionId, SseConnection.noop());
    }

    /** Creates a new persisted generation with the given process-local SSE connection. */
    public Session createSession(String sessionId, SseConnection connection) {
        final var creation = createAndInstall(sessionId, connection);
        final var previous = creation.replaced();
        if (previous != null) {
            previous.close();
            logger.debug("Replaced existing session: {}", sessionId);
        }
        logger.info("Session created: {}", sessionId);
        return creation.created();
    }

    /** Returns the local runtime, reconstructing it from a non-expired snapshot when absent. */
    public Optional<Session> getSession(@Nullable String sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        final var local = sessions.get(sessionId);
        if (local != null) {
            return Optional.of(local);
        }
        return hydrate(sessionId);
    }

    /** Returns the process-local runtime without consulting the snapshot store. */
    public Optional<Session> getLocalSession(@Nullable String sessionId) {
        return Optional.ofNullable(sessionId == null ? null : sessions.get(sessionId));
    }

    private Optional<Session> hydrate(String sessionId) {
        final var lifecycleLock = acquireLifecycleLock(sessionId);
        lifecycleLock.lock();
        try {
            final var local = sessions.get(sessionId);
            if (local != null) {
                return Optional.of(local);
            }
            return hydrateAbsent(sessionId);
        } finally {
            lifecycleLock.unlock();
            releaseLifecycleLock(sessionId, lifecycleLock);
        }
    }

    private Optional<Session> hydrateAbsent(String sessionId) {
        final var persisted = store.find(sessionId);
        if (persisted.isEmpty()) {
            return Optional.empty();
        }
        final var snapshot = persisted.orElseThrow();
        if (snapshot.state() == SessionState.CLOSED || !snapshot.expiresAt().isAfter(clock.instant())) {
            store.terminate(snapshot.key());
            return Optional.empty();
        }
        final Session hydrated;
        try {
            hydrated = runtime(snapshot, SseConnection.noop());
        } catch (IllegalArgumentException e) {
            logger.warn(
                    "Cannot restore incompatible session snapshot: sessionId={}, protocolVersion={}",
                    sessionId,
                    snapshot.protocolVersion());
            return Optional.empty();
        }
        sessions.put(sessionId, hydrated);
        return Optional.of(hydrated);
    }

    /** Returns all process-local runtime sessions. */
    public Collection<Session> allSessions() {
        return sessions.values();
    }

    /** Removes and closes the current generation for the session ID. */
    public void removeSession(String sessionId) {
        final var local = sessions.remove(sessionId);
        if (local != null) {
            store.terminate(local.key());
            local.close();
            logger.info("Session removed: {}", sessionId);
            return;
        }
        store.find(sessionId).ifPresent(snapshot -> store.terminate(snapshot.key()));
    }

    /** Starts the background janitor that closes expired sessions. */
    public void startJanitor(Duration ttl, Duration interval) {
        this.ttl = ttl;
        this.expiryRefreshMargin = ttl.dividedBy(2);
        final var ttlNanos = ttl.toNanos();
        janitor = new AbstractJanitor("session-janitor") {
            @Override
            protected void sweep() {
                SessionManager.this.sweep(ttlNanos);
            }
        };
        janitor.start(interval);
        logger.debug("Session janitor started (interval={}ms, ttl={}ms)", interval.toMillis(), ttlNanos / 1_000_000);
    }

    /** One janitor pass: closes and evicts local sessions that are closed or idle past the TTL. */
    void sweep(long ttlNanos) {
        var now = System.nanoTime();
        for (var session : sessions.values()) {
            try {
                var expired = now - session.lastActivityNanos() > ttlNanos;
                if (session.state() == SessionState.CLOSED || expired) {
                    removeIfCurrent(session);
                }
            } catch (RuntimeException e) {
                logger.warn("Error while sweeping session: {}", session.id(), e);
            }
        }
    }

    private void removeIfCurrent(Session expected) {
        if (sessions.remove(expected.id(), expected)) {
            store.terminate(expected.key());
            expected.close();
        }
    }

    private Session runtime(SessionSnapshot snapshot, SseConnection connection) {
        return Session.fromSnapshot(snapshot, connection, this::persist, this::touch);
    }

    private Creation createAndInstall(String sessionId, SseConnection connection) {
        return withLifecycleLock(sessionId, () -> {
            final var key = new SessionKey(sessionId, UUID.randomUUID().toString());
            final var snapshot = store.create(key, expiresAt());
            final var created = runtime(snapshot, connection);
            return new Creation(created, sessions.put(sessionId, created));
        });
    }

    /**
     * Runs {@code action} while holding the session's lifecycle lock, so creation, snapshot
     * persistence and expiry refreshes for one id never interleave. The lock is private to this
     * manager and reentrant: {@link #evictLocal(Session)} closes the session from inside the lock,
     * which re-enters through {@link #persist(Session)}.
     */
    private <T> T withLifecycleLock(String sessionId, Supplier<T> action) {
        final var lifecycleLock = acquireLifecycleLock(sessionId);
        lifecycleLock.lock();
        try {
            return action.get();
        } finally {
            lifecycleLock.unlock();
            releaseLifecycleLock(sessionId, lifecycleLock);
        }
    }

    private void withLifecycleLock(String sessionId, Runnable action) {
        withLifecycleLock(sessionId, () -> {
            action.run();
            return null;
        });
    }

    private LifecycleLock acquireLifecycleLock(String sessionId) {
        return lifecycleLocks.compute(sessionId, (id, current) -> {
            final var lock = current == null ? new LifecycleLock() : current;
            lock.retain();
            return lock;
        });
    }

    private void releaseLifecycleLock(String sessionId, LifecycleLock lifecycleLock) {
        lifecycleLocks.computeIfPresent(sessionId, (id, current) -> {
            if (current != lifecycleLock) {
                return current;
            }
            return lifecycleLock.release() ? null : lifecycleLock;
        });
    }

    private void persist(Session session) {
        if (sessions.get(session.id()) != session) {
            return;
        }
        withLifecycleLock(session.id(), () -> {
            if (sessions.get(session.id()) != session) {
                return;
            }
            try {
                final var expected = session.persistedSnapshot();
                final var updated = session.snapshot(expiresAt(), expected.revision() + 1);
                if (store.compareAndSet(expected, updated)) {
                    session.persistedSnapshot(updated);
                    return;
                }
                logger.warn("Session snapshot update lost ownership: {}", session.id());
                evictLocal(session);
            } catch (RuntimeException e) {
                logger.warn("Failed to persist session snapshot: {}", session.id(), e);
            }
        });
    }

    private void touch(Session session) {
        if (sessions.get(session.id()) != session || !refreshDue(session, clock.millis())) {
            return;
        }
        if (pendingExpiryRefreshes.putIfAbsent(session.key(), Boolean.TRUE) != null) {
            return;
        }
        try {
            persistenceExecutor.execute(() -> refreshExpiry(session));
        } catch (RejectedExecutionException e) {
            pendingExpiryRefreshes.remove(session.key());
            logger.debug("Session expiry refresh rejected during shutdown: {}", session.id());
        }
    }

    private void refreshExpiry(Session session) {
        try {
            refreshExpiryIfDue(session);
        } finally {
            pendingExpiryRefreshes.remove(session.key());
        }
    }

    private void refreshExpiryIfDue(Session session) {
        withLifecycleLock(session.id(), () -> {
            if (sessions.get(session.id()) != session) {
                return;
            }
            final var now = clock.instant();
            final var expected = session.persistedSnapshot();
            final var refreshAt = expected.expiresAt().minus(expiryRefreshMargin);
            if (now.isBefore(refreshAt)) {
                return;
            }
            final var updatedExpiry = now.plus(ttl);
            try {
                if (store.touch(session.key(), updatedExpiry)) {
                    session.persistedSnapshot(refreshed(expected, updatedExpiry));
                } else {
                    logger.warn("Session expiry refresh lost ownership: {}", session.id());
                    evictLocal(session);
                }
            } catch (RuntimeException e) {
                logger.warn("Failed to refresh session snapshot expiry: {}", session.id(), e);
            }
        });
    }

    private boolean refreshDue(Session session, long nowMillis) {
        final var refreshAtMillis =
                session.persistedSnapshot().expiresAt().toEpochMilli() - expiryRefreshMargin.toMillis();
        return nowMillis >= refreshAtMillis;
    }

    private static SessionSnapshot refreshed(SessionSnapshot expected, Instant expiresAt) {
        return new SessionSnapshot(
                expected.key(),
                expected.state(),
                expected.protocolVersion(),
                expected.enabledExtensionIds(),
                expected.loggingLevel(),
                expiresAt,
                expected.revision() + 1);
    }

    private void evictLocal(Session session) {
        if (sessions.remove(session.id(), session)) {
            session.close();
        }
    }

    private Instant expiresAt() {
        return clock.instant().plus(ttl);
    }

    @Override
    public void close() {
        if (janitor != null) {
            janitor.close();
        }
        final var local = List.copyOf(sessions.values());
        sessions.clear();
        local.forEach(this::closeLocal);
        try {
            store.close();
            logger.debug("SessionManager closed");
        } catch (Exception e) {
            logger.warn("Failed to close session store", e);
        }
    }

    private void closeLocal(Session session) {
        try {
            session.close();
        } catch (RuntimeException e) {
            logger.warn("Failed to close session: {}", session.id(), e);
        }
    }

    private record Creation(Session created, @Nullable Session replaced) {}

    private static final class LifecycleLock {
        private final ReentrantLock delegate = new ReentrantLock();
        private int users;

        void lock() {
            delegate.lock();
        }

        void unlock() {
            delegate.unlock();
        }

        void retain() {
            users++;
        }

        boolean release() {
            return --users == 0;
        }
    }
}
