/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.session;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
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
 * Session lifecycle through {@link SessionManager} over an {@link InMemorySessionStore}. Removal
 * needs a live generation, so each operation creates one and removes it; every worker thread owns
 * its own session ID. Idle expiry sweeps every local session, so each worker sweeps its own manager.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Fork(value = 1, jvmArgsAppend = "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn")
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Threads(3)
public class SessionManagerBenchmark {

    private SessionManager manager;

    @Setup(Level.Trial)
    public void setUp() {
        manager = new SessionManager(new InMemorySessionStore());
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        manager.close();
    }

    @State(Scope.Thread)
    public static class OwnedId {
        private final String sessionId = UUID.randomUUID().toString();
    }

    @State(Scope.Thread)
    public static class OwnedManager {
        private final String sessionId = UUID.randomUUID().toString();
        private SessionManager manager;

        @Setup(Level.Trial)
        public void setUp() {
            manager = new SessionManager(new InMemorySessionStore());
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            manager.close();
        }
    }

    @Benchmark
    public void createAndRemove(OwnedId owned) {
        manager.createSession(owned.sessionId);
        manager.removeSession(owned.sessionId);
    }

    @Benchmark
    public void createAndExpire(OwnedManager owned) {
        owned.manager.createSession(owned.sessionId);
        owned.manager.sweep(-1);
    }
}
