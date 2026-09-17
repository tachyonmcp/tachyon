/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.session;

import dev.tachyonmcp.api.server.domain.RequestId;
import dev.tachyonmcp.api.server.session.SessionIdGenerator;
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
 * Steady-state append throughput with one session per worker thread: every session fills its
 * per-session cap during warmup, so each measured append also runs the eviction path.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Threads(3)
public class InMemorySessionEventStoreBenchmark {

    private static final long TIMESTAMP = 1_767_225_600_000L;

    private InMemorySessionEventStore store;

    @Setup(Level.Trial)
    public void setUp() {
        store = new InMemorySessionEventStore();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        store.close();
    }

    @State(Scope.Thread)
    public static class Writer {
        private String sessionId;
        private int seq;

        @Setup(Level.Trial)
        public void setUp() {
            sessionId = SessionIdGenerator.DEFAULT.generate(null, null);
        }
    }

    @Benchmark
    public void append(Writer writer) {
        store.append(
                new SessionEvent.RequestEvent(writer.sessionId, RequestId.of(writer.seq++), "ping", "{}", TIMESTAMP));
    }
}
