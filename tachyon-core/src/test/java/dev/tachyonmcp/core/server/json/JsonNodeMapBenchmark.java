/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import tools.jackson.databind.JsonNode;

/**
 * Converting a {@code _meta}-style {@code Map<String, ?>} into the {@code Map<String, JsonNode>}
 * generated protocol records carry: {@link JsonUtils#toJsonNodeMap} (direct {@code JsonNodeFactory}
 * trees) against the per-entry {@code valueToTree} it replaced. Whole-map {@code convertValue} and
 * {@code valueToTree} measured slower than both.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class JsonNodeMapBenchmark {

    @Param({"meta", "frontmatter"})
    public String shape;

    private Map<String, Object> values;

    @Setup
    public void setUp() {
        values = switch (shape) {
            case "meta" ->
                Map.of(
                        "progressToken",
                        "req-42",
                        "io.modelcontextprotocol/related-task",
                        Map.of("taskId", "786512e2-9e0d-44bd-8f29-789f320fe840"));
            case "frontmatter" -> {
                var frontmatter = new LinkedHashMap<String, Object>();
                frontmatter.put("name", "pdf-processing");
                frontmatter.put("description", "Extract text and tables from PDF files, fill forms, merge documents.");
                frontmatter.put("license", "Apache-2.0");
                frontmatter.put("allowed-tools", List.of("Bash(python:*)", "Read", "Write"));
                frontmatter.put("metadata", Map.of("author", "example-org", "version", 2, "stable", true));
                yield frontmatter;
            }
            default -> throw new IllegalArgumentException(shape);
        };
    }

    /** The previous {@code toJsonNodeMap}: one {@code valueToTree} buffer round trip per entry. */
    @Benchmark
    public Map<String, JsonNode> valueToTreePerEntry() {
        var result = new LinkedHashMap<String, JsonNode>(values.size());
        values.forEach((key, value) -> result.put(key, JsonUtils.mapper().valueToTree(value)));
        return result;
    }

    @Benchmark
    public Map<String, JsonNode> toJsonNodeMap() {
        return JsonUtils.toJsonNodeMap(values);
    }
}
