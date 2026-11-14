/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.conformance;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

class ConformanceRunner {

    private static final String CONFORMANCE_PACKAGE = "@modelcontextprotocol/conformance";

    private static final String CONFORMANCE_REPOSITORY = "github:modelcontextprotocol/conformance";

    private final String serverUrl;

    private final String packageSpec;

    private final String outputDir;

    private final String baselineFile;

    ConformanceRunner(String serverUrl, String packageSpec, String outputDir, String baselineFile) {
        this.serverUrl = serverUrl;
        this.packageSpec = packageSpec;
        this.outputDir = outputDir;
        this.baselineFile = baselineFile;
    }

    static boolean isNpxAvailable() {
        try {
            var process =
                    new ProcessBuilder("which", "npx").redirectErrorStream(true).start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns the npx package spec of {@code ref}: an npm release for a version, otherwise the upstream
     * git repository at that commit (or branch).
     */
    static String packageSpec(String ref) {
        return Character.isDigit(ref.charAt(0)) && ref.indexOf('.') > 0
                ? CONFORMANCE_PACKAGE + "@" + ref
                : CONFORMANCE_REPOSITORY + "#" + ref;
    }

    List<String> listScenarios(String specVersion) throws IOException, InterruptedException {
        var args = List.of("npx", "--yes", packageSpec, "list", "--server", "--spec-version", specVersion);
        var result = exec(args, 10, TimeUnit.MINUTES);
        var output = result.outputLines();
        var names = output.stream()
                .dropWhile(l -> !l.startsWith("Server scenarios"))
                .filter(l -> l.startsWith("  - "))
                .map(l -> {
                    var bracket = l.indexOf('[');
                    return (bracket > 0 ? l.substring(4, bracket) : l.substring(4)).strip();
                })
                .toList();
        if (!result.passed() || names.isEmpty()) {
            throw new IllegalStateException("Conformance list found no server scenarios (exit " + result.exitCode()
                    + "):\n" + String.join("\n", output));
        }
        return names;
    }

    ConformanceResult runSuite(String suiteName, String protocolVersion) throws IOException, InterruptedException {
        return run("--suite", suiteName, "--spec-version", protocolVersion);
    }

    ConformanceResult runScenario(String scenario, String protocolVersion) throws IOException, InterruptedException {
        return run("--scenario", scenario, "--spec-version", protocolVersion);
    }

    private ConformanceResult run(String... extraArgs) throws IOException, InterruptedException {
        var args = new ArrayList<>(List.of(
                "npx",
                "--yes",
                packageSpec,
                "server",
                "--url",
                serverUrl,
                "--expected-failures",
                baselineFile,
                "--output-dir",
                outputDir));
        args.addAll(List.of(extraArgs));
        return exec(args, 3, TimeUnit.MINUTES);
    }

    private static ConformanceResult exec(List<String> args, long timeout, TimeUnit unit)
            throws IOException, InterruptedException {
        var process = new ProcessBuilder(args).redirectErrorStream(true).start();

        var outputLines = new ArrayList<String>();
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                outputLines.add(line);
            }
        }

        var finished = process.waitFor(timeout, unit);
        return new ConformanceResult(finished, finished ? process.exitValue() : -1, outputLines);
    }

    public record ConformanceResult(boolean finished, int exitCode, List<String> outputLines) {
        public boolean passed() {
            return finished && exitCode == 0;
        }
    }
}
