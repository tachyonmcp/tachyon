/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.session;

import dev.tachyonmcp.api.server.domain.RequestId;
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
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Threads(3)
public class InMemorySessionEventStoreBenchmark {

    private static final long MIN_OPS_PER_SEC = 1_000_000;

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
    public static class SessionState {
        private final String sessionId = "sess_" + Thread.currentThread().threadId();
        private int seq;
    }

    @Benchmark
    public void append(SessionState state) {
        store.append(new SessionEvent.RequestEvent(
                state.sessionId, RequestId.of(state.seq++), "ping", "{}", System.currentTimeMillis()));
    }

    public static void main(String[] args) throws RunnerException {
        Options options = new OptionsBuilder()
                .include(InMemorySessionEventStoreBenchmark.class.getSimpleName())
                .build();
        var results = new Runner(options).run();
        var score = results.iterator().next().getPrimaryResult().getScore();
        if (score < MIN_OPS_PER_SEC) {
            throw new AssertionError(
                    "InMemorySessionEventStore.append() throughput regression: %.0f ops/sec, expected >= %d"
                            .formatted(score, MIN_OPS_PER_SEC));
        }
    }
}
