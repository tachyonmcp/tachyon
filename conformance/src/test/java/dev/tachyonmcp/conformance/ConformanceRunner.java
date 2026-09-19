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

    private final String serverUrl;

    private final String conformanceVersion;

    private final String outputDir;

    private final String baselineFile;

    ConformanceRunner(String serverUrl, String conformanceVersion, String outputDir, String baselineFile) {
        this.serverUrl = serverUrl;
        this.conformanceVersion = conformanceVersion;
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

    List<String> listScenarios(String protocolVersion) throws IOException, InterruptedException {
        var args = List.of("npx", "--yes", CONFORMANCE_PACKAGE + "@" + conformanceVersion, "list");

        var process = new ProcessBuilder(args).redirectErrorStream(true).start();
        var outputLines = new ArrayList<String>();
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                outputLines.add(line);
            }
        }
        process.waitFor(30, TimeUnit.SECONDS);

        var serverLines = outputLines.stream()
                .dropWhile(l -> !l.startsWith("Server scenarios"))
                .skip(1)
                .takeWhile(l -> !l.startsWith("Client scenarios"))
                .filter(l -> l.startsWith("  - "))
                .toList();

        return createScenarios(protocolVersion, serverLines);
    }

    static ArrayList<String> createScenarios(String protocolVersion, List<String> serverLines) {
        var scenarios = new ArrayList<String>();
        for (var line : serverLines) {
            var bracket = line.indexOf('[');
            var name = bracket > 0
                    ? line.substring(4, bracket).strip()
                    : line.substring(4).strip();
            if (bracket > 0) {
                var versionsStr = line.substring(bracket + 1, line.indexOf(']', bracket));
                var versions = List.of(versionsStr.split(",\\s*"));
                if (!versions.contains(protocolVersion)) {
                    continue;
                }
            }
            scenarios.add(name);
        }
        return scenarios;
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
                CONFORMANCE_PACKAGE + "@" + conformanceVersion,
                "server",
                "--url",
                serverUrl,
                "--expected-failures",
                baselineFile,
                "--output-dir",
                outputDir));
        args.addAll(List.of(extraArgs));

        var process = new ProcessBuilder(args).redirectErrorStream(true).start();

        var outputLines = new ArrayList<String>();
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                outputLines.add(line);
            }
        }

        var finished = process.waitFor(3, TimeUnit.MINUTES);
        return new ConformanceResult(finished, process.exitValue(), outputLines);
    }

    public record ConformanceResult(boolean finished, int exitCode, List<String> outputLines) {
        public boolean passed() {
            return finished && exitCode == 0;
        }
    }
}
