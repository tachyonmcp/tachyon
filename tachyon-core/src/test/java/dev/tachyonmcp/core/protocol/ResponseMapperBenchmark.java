/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.protocol;

import dev.tachyonmcp.api.server.config.ServerIdentity;
import dev.tachyonmcp.api.server.domain.ServerCapabilities;
import dev.tachyonmcp.api.server.domain.TextContent;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.codecs.McpResponseMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Response mapping that inlines a generated model as a JSON tree: a completed task's
 * {@code tasks/get} result (the whole {@code CallToolResult}) and {@code server/discover}
 * (the server's {@code Implementation}, twice).
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class ResponseMapperBenchmark {

    private final McpResponseMapper mapper = new McpResponseMapper();
    private TaskSnapshot completedTask;
    private ServerCapabilities capabilities;
    private ServerIdentity identity;

    @Setup
    public void setUp() {
        var result = ToolResult.Success.of(
                Map.of("temperature", 21.5, "unit", "C", "conditions", List.of("sunny", "dry")),
                List.of(TextContent.of("It is 21.5 C and sunny in Berlin.")));
        completedTask =
                TaskSnapshot.completed("786512e2-9e0d-44bd-8f29-789f320fe840", Instant.EPOCH, Instant.EPOCH, 3, result);
        capabilities =
                ServerCapabilities.builder().logging(true).completions(false).build();
        identity = ServerIdentity.builder()
                .name("weather")
                .version("1.4.2")
                .title("Weather")
                .description("Forecasts and current conditions")
                .websiteUrl("https://example.com/weather")
                .build();
    }

    @Benchmark
    public Object completedTaskResult() {
        return mapper.getTaskResult(completedTask);
    }

    @Benchmark
    public Object discoverResult() {
        return mapper.discoverResult(List.of("2026-07-28", "2025-11-25"), capabilities, identity, Map.of());
    }
}
