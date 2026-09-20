/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.spring.boot;

import dev.tachyonmcp.api.server.domain.ServerError;
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
import java.util.regex.Pattern;

/**
 * Times each inbound MCP operation into {@code mcp.server.operation.duration}, tagged by method and
 * outcome.
 */
final class TachyonMetricsListener implements ObservationListener {

    /** The OpenTelemetry semantic-convention name for an MCP server operation's duration. */
    static final String OPERATION_DURATION = "mcp.server.operation.duration";

    /** Compiled once: {@link #outcome} runs on every completed operation. */
    private static final Pattern CAMEL_HUMP = Pattern.compile("([a-z])([A-Z])");

    /**
     * Tag value for a method the server does not serve. The dispatcher builds an
     * {@link OperationInfo} from the raw wire method before resolving a handler, so tagging that
     * value directly would let a client mint unbounded meters.
     */
    static final String UNKNOWN_METHOD = "unknown";

    private final MeterRegistry registry;
    private final Map<OperationInfo, Long> started = new ConcurrentHashMap<>();
    private final Map<TimerKey, Timer> timers = new ConcurrentHashMap<>();

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
        timer(method(info, outcome), outcome(outcome)).record(end - start, TimeUnit.NANOSECONDS);
    }

    /**
     * Both tag values come from bounded sets, so the cache is bounded too. Kept rather than calling
     * {@code registry.timer(...)} per operation: this runs on every inbound message, and the cache
     * trades a fixed map for a {@code Meter.Id} and tag list per call.
     */
    private Timer timer(String method, String outcome) {
        return timers.computeIfAbsent(
                new TimerKey(method, outcome),
                key -> Timer.builder(OPERATION_DURATION)
                        .description("Inbound MCP operation duration")
                        .tag("mcp.method.name", key.method())
                        .tag("outcome", key.outcome())
                        .register(registry));
    }

    /**
     * The method name, or {@link #UNKNOWN_METHOD} when no handler matched: a request rejected as
     * {@code METHOD_NOT_FOUND}, or an ignored notification. Any other outcome reached a handler, so
     * the name is one the server serves.
     */
    static String method(OperationInfo info, OperationOutcome outcome) {
        return switch (outcome) {
            case OperationOutcome.Rejected rejected
            when rejected.error() != null && rejected.error().kind() == ServerError.Kind.METHOD_NOT_FOUND ->
                UNKNOWN_METHOD;
            case OperationOutcome.NotificationIgnored ignored -> UNKNOWN_METHOD;
            default -> info.method();
        };
    }

    static String outcome(OperationOutcome outcome) {
        return CAMEL_HUMP
                .matcher(outcome.getClass().getSimpleName())
                .replaceAll("$1_$2")
                .toLowerCase(Locale.ROOT);
    }

    private record TimerKey(String method, String outcome) {}
}
