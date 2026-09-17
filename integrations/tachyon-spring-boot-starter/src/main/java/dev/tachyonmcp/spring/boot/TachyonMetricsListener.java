/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.spring.boot;

import dev.tachyonmcp.core.server.observability.ObservationListener;
import dev.tachyonmcp.core.server.observability.ObservationScope;
import dev.tachyonmcp.core.server.observability.OperationInfo;
import dev.tachyonmcp.core.server.observability.OperationOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Times each inbound MCP operation into {@code mcp.server.operations}, tagged by method and outcome. */
final class TachyonMetricsListener implements ObservationListener {

    static final String OPERATIONS = "mcp.server.operations";

    private final MeterRegistry registry;
    private final Map<OperationInfo, Long> started = new ConcurrentHashMap<>();

    TachyonMetricsListener(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public ObservationScope start(OperationInfo info) {
        started.put(info, System.nanoTime());
        return ObservationScope.NOOP;
    }

    @Override
    public void complete(OperationInfo info, OperationOutcome outcome) {
        final var start = started.remove(info);
        if (start == null) return;
        final var establishmentNanos = info.establishmentNanos();
        final var end = establishmentNanos != null ? establishmentNanos : System.nanoTime();
        Timer.builder(OPERATIONS)
                .description("Inbound MCP operation duration")
                .tag("mcp.method.name", info.method())
                .tag("outcome", outcome(outcome))
                .register(registry)
                .record(end - start, TimeUnit.NANOSECONDS);
    }

    static String outcome(OperationOutcome outcome) {
        return outcome.getClass()
                .getSimpleName()
                .replaceAll("([a-z])([A-Z])", "$1_$2")
                .toLowerCase(Locale.ROOT);
    }
}
