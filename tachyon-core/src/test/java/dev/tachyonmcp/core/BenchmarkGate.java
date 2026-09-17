/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core;

import dev.tachyonmcp.core.server.session.InMemorySessionEventStoreBenchmark;
import dev.tachyonmcp.core.server.session.InMemorySessionStoreBenchmark;
import java.util.ArrayList;
import java.util.Map;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.CommandLineOptions;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Runs every JMH benchmark in this module and fails when a gated benchmark drops below its
 * throughput floor. Accepts the standard JMH command line (e.g. {@code -prof gc}, {@code -t 8},
 * or a benchmark regex) so the same entry point serves CI and local profiling.
 */
public final class BenchmarkGate {

    private static final Map<String, Double> MIN_OPS_PER_SEC = Map.of(
            InMemorySessionEventStoreBenchmark.class.getName() + ".append", 1_000_000d,
            InMemorySessionStoreBenchmark.class.getName() + ".find", 10_000_000d,
            InMemorySessionStoreBenchmark.class.getName() + ".touch", 1_000_000d,
            InMemorySessionStoreBenchmark.class.getName() + ".compareAndSet", 1_000_000d);

    private BenchmarkGate() {}

    public static void main(String[] args) throws Exception {
        final var options =
                new OptionsBuilder().parent(new CommandLineOptions(args)).build();
        final var results = new Runner(options).run();
        final var regressions = new ArrayList<String>();
        for (final var result : results) {
            final var name = result.getParams().getBenchmark();
            final var score = result.getPrimaryResult().getScore();
            final var floor = MIN_OPS_PER_SEC.get(name);
            if (floor != null && score < floor) {
                regressions.add("%s: %.0f ops/sec, expected >= %.0f".formatted(name, score, floor));
            }
        }
        if (!regressions.isEmpty()) {
            throw new AssertionError("Throughput regression:\n" + String.join("\n", regressions));
        }
    }
}
