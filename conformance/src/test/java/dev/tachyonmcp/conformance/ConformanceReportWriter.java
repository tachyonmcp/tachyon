/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.conformance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

class ConformanceReportWriter {

    private static final Pattern SCENARIO_LINE = Pattern.compile("^[✗✓] (.+): (\\d+) passed, (\\d+) failed$");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    record ScenarioResult(String name, int passed, int failed) {}

    static List<String> parseBaseline(Path baselinePath) throws IOException {
        try (var lines = Files.lines(baselinePath)) {
            return lines.filter(l -> l.startsWith("  - "))
                    .map(l -> l.substring(4).strip())
                    .toList();
        }
    }

    static List<ScenarioResult> parseResults(List<String> outputLines) {
        return outputLines.stream()
                .dropWhile(l -> !l.startsWith("=== SUMMARY ==="))
                .map(l -> SCENARIO_LINE.matcher(l.trim()))
                .filter(Matcher::matches)
                .map(m -> new ScenarioResult(m.group(1), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))))
                .toList();
    }

    /**
     * Describes the failed checks upstream saved for {@code scenario} under {@code outputDir}, one per
     * line; upstream writes each scenario to {@code server-<scenario>-<timestamp>/checks.json}.
     */
    static String failedChecks(Path outputDir, String scenario) throws IOException {
        var dirName = Pattern.compile("server-" + Pattern.quote(scenario) + "-\\d{4}-.*");
        var lines = new ArrayList<String>();
        try (var dirs = Files.list(outputDir)) {
            for (var dir : dirs.filter(
                            d -> dirName.matcher(d.getFileName().toString()).matches())
                    .toList()) {
                var checks = dir.resolve("checks.json");
                if (!Files.exists(checks) || !(MAPPER.readTree(checks.toFile()) instanceof ArrayNode array)) continue;
                for (var check : array) {
                    if (!"FAILURE".equals(check.path("status").asString())) continue;
                    lines.add("- " + check.path("id").asString() + ": "
                            + check.path("description").asString()
                            + "\n    error: " + check.path("errorMessage").asString("<none>")
                            + "\n    details: " + check.path("details"));
                }
            }
        }
        return String.join("\n", lines);
    }
}
