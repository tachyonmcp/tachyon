/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.conformance;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;

class ConformanceRunner {

    private static final String CONFORMANCE_PACKAGE = "@modelcontextprotocol/conformance";

    private static final String CONFORMANCE_REPOSITORY = "github:modelcontextprotocol/conformance";

    // two calls (run + list) must fit the 5 min @Timeout
    private static final Duration DEADLINE = Duration.ofMinutes(2);

    private final String packageSpec;

    ConformanceRunner(String packageSpec) {
        this.packageSpec = packageSpec;
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

    /**
     * Lists the server scenarios upstream selects for {@code specVersion} (all of them when null),
     * each mapped to its tag: the spec versions it applies to, or {@code extension}.
     */
    Map<String, String> listScenarios(@Nullable String specVersion) throws IOException, InterruptedException {
        var result = exec(specVersion, "list", "--server");
        var scenarios = new LinkedHashMap<String, String>();
        result.outputLines().stream()
                .dropWhile(l -> !l.startsWith("Server scenarios"))
                .filter(l -> l.startsWith("  - "))
                .forEach(l -> {
                    var bracket = l.indexOf('[');
                    if (bracket > 0) {
                        scenarios.put(
                                l.substring(4, bracket).strip(), l.substring(bracket + 1, l.indexOf(']', bracket)));
                    } else {
                        scenarios.put(l.substring(4).strip(), "");
                    }
                });
        if (!result.passed() || scenarios.isEmpty()) {
            throw new IllegalStateException("Conformance list found no server scenarios (exit " + result.exitCode()
                    + "):\n" + String.join("\n", result.outputLines()));
        }
        return scenarios;
    }

    /** Runs every server scenario upstream selects for {@code specVersion} (all of them when null) in one process. */
    ConformanceResult runSuite(String serverUrl, @Nullable String specVersion, String outputDir)
            throws IOException, InterruptedException {
        return exec(specVersion, "server", "--url", serverUrl, "--suite", "all", "--output-dir", outputDir);
    }

    private ConformanceResult exec(@Nullable String specVersion, String... command)
            throws IOException, InterruptedException {
        var args = new ArrayList<>(List.of("npx", "--yes", packageSpec));
        args.addAll(List.of(command));
        if (specVersion != null) {
            args.addAll(List.of("--spec-version", specVersion));
        }
        var process = new ProcessBuilder(args).redirectErrorStream(true).start();
        var timedOut = new AtomicBoolean();
        process.onExit().orTimeout(DEADLINE.toMillis(), TimeUnit.MILLISECONDS).whenComplete((p, failure) -> {
            if (failure != null) {
                timedOut.set(true);
                destroyTree(process);
            }
        });

        var outputLines = new ArrayList<String>();
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                outputLines.add(line);
            }
            var exitCode = process.waitFor();
            if (timedOut.get()) {
                throw new IllegalStateException("Conformance '" + String.join(" ", command) + "' timed out after "
                        + DEADLINE.toMinutes() + " min:\n" + String.join("\n", outputLines));
            }
            return new ConformanceResult(exitCode, outputLines);
        } finally {
            destroyTree(process);
        }
    }

    private static void destroyTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    record ConformanceResult(int exitCode, List<String> outputLines) {
        boolean passed() {
            return exitCode == 0;
        }
    }
}
