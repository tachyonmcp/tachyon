/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.session;

import dev.tachyonmcp.api.server.session.SessionIdGenerator;
import dev.tachyonmcp.core.runtime.SessionState;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Per-session snapshot operations against a store holding {@link #LIVE_SESSIONS} sessions. Each
 * worker thread owns one of them, {@link #STRIDE} creations apart from any other worker's, so the
 * measured contention comes from the store and not from map nodes that happen to share a cache
 * line. Every call targets the caller's own generation and must succeed, mirroring how {@link
 * SessionManager} drives the store.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Threads(3)
public class InMemorySessionStoreBenchmark {

    private static final int LIVE_SESSIONS = 4096;
    private static final int STRIDE = 128;
    private static final Instant EXPIRES_AT = Instant.parse("2026-01-01T00:00:00Z");

    private InMemorySessionStore store;
    private SessionKey[] keys;
    private final AtomicInteger nextWorker = new AtomicInteger();

    @Setup(Level.Trial)
    public void setUp() {
        store = new InMemorySessionStore();
        keys = new SessionKey[LIVE_SESSIONS];
        for (int i = 0; i < LIVE_SESSIONS; i++) {
            keys[i] = new SessionKey(
                    SessionIdGenerator.DEFAULT.generate(null, null),
                    UUID.randomUUID().toString());
            store.create(keys[i], EXPIRES_AT);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        store.close();
    }

    @State(Scope.Thread)
    public static class OwnedSession {
        private InMemorySessionStore store;
        private SessionKey key;
        private SessionSnapshot current;

        @Setup(Level.Trial)
        public void setUp(InMemorySessionStoreBenchmark benchmark) {
            store = benchmark.store;
            key = benchmark.keys[benchmark.nextWorker.getAndIncrement() * STRIDE % LIVE_SESSIONS];
            current = store.find(key.sessionId()).orElseThrow();
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            final var persisted = store.find(key.sessionId()).orElseThrow();
            if (!persisted.key().equals(key) || persisted.revision() < current.revision()) {
                throw new IllegalStateException("Benchmark lost ownership of " + key + ": " + persisted);
            }
        }
    }

    @Benchmark
    public Optional<SessionSnapshot> find(OwnedSession session) {
        return store.find(session.key.sessionId());
    }

    @Benchmark
    public boolean touch(OwnedSession session) {
        if (!store.touch(session.key, EXPIRES_AT)) {
            throw new IllegalStateException("Touch rejected for " + session.key);
        }
        return true;
    }

    @Benchmark
    public boolean compareAndSet(OwnedSession session) {
        final var expected = session.current;
        final var updated = new SessionSnapshot(
                expected.key(),
                SessionState.ACTIVE,
                "2025-11-25",
                expected.enabledExtensionIds(),
                expected.loggingLevel(),
                EXPIRES_AT,
                expected.revision() + 1);
        final var swapped = store.compareAndSet(expected, updated);
        session.current = updated;
        return swapped;
    }
}
