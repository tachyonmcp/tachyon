/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import dev.tachyonmcp.conformance.ConformanceReportWriter.ScenarioResult;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.transport.netty.NettyServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Tag("conformance")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.CONCURRENT)
public abstract class AbstractServerConformanceTest {

    /** The pinned upstream {@code main} commit. */
    protected static final String LATEST_MAIN_SHA = "96a6f9478b7f2e125cf0360a213383cf57533893";

    private static final Logger log = LoggerFactory.getLogger(AbstractServerConformanceTest.class);
    private final ConformanceRunner runner;
    private final String suiteName;
    private final @Nullable String specVersion;
    private final ServerEngine engine;
    private final NettyServer transport;

    /**
     * @param serverFactory the server under test
     * @param ref the upstream conformance release ({@code 0.2.0-alpha.12}) or git commit/branch
     * @param specVersion the {@code --spec-version} of the run, or {@code null} for upstream's bare
     *     {@code --suite all}, which alone reaches the {@code extension} scenarios. A bare run also replays
     *     the timeline scenarios at upstream's default wire; the spec-version suites judge those, so it
     *     judges only the extension scenarios
     * @param stateful whether the server keeps sessions
     */
    protected AbstractServerConformanceTest(
            AbstractConformanceServer serverFactory, String ref, @Nullable String specVersion, boolean stateful) {
        var packageSpec = ConformanceRunner.packageSpec(ref);
        this.runner = new ConformanceRunner(packageSpec);
        this.suiteName = getClass()
                .getPackageName()
                .substring(getClass().getPackageName().lastIndexOf('.') + 2)
                .replace('_', '-');
        this.specVersion = specVersion;
        log.info("[conformance] {} suite={} spec={}", getClass().getSimpleName(), suiteName, packageSpec);
        this.engine = serverFactory.startServer(stateful);
        try {
            this.transport = new NettyServer(0, engine);
        } catch (RuntimeException e) {
            engine.close();
            throw e;
        }
    }

    @AfterAll
    void stopServer() {
        try {
            transport.close();
        } finally {
            engine.close();
        }
    }

    @TestFactory
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    Stream<DynamicTest> serverConformance() throws Exception {
        assumeTrue(ConformanceRunner.isNpxAvailable(), "npx is not available on this system");

        var outputDir =
                Path.of("target/surefire-reports", suiteName + "-" + getClass().getSimpleName());
        deleteRecursively(outputDir);
        Files.createDirectories(outputDir);

        var result =
                runner.runSuite("http://localhost:" + transport.port() + "/mcp", specVersion, outputDir.toString());
        Files.write(outputDir.resolve("conformance.log"), result.outputLines());
        var executed = ConformanceReportWriter.parseResults(result.outputLines());
        if (executed.isEmpty()) {
            fail("Conformance run printed no summary (exit " + result.exitCode() + "):\n"
                    + String.join("\n", result.outputLines()));
        }

        var listed = runner.listScenarios(specVersion);
        var judged = specVersion == null
                ? listed.entrySet().stream()
                        .filter(e -> "extension".equals(e.getValue()))
                        .map(Map.Entry::getKey)
                        .toList()
                : List.copyOf(listed.keySet());
        var results = executed.stream().collect(Collectors.toMap(ScenarioResult::name, Function.identity()));

        var baselinePath = Path.of("conformance-baseline-" + suiteName + ".yml");
        var baseline =
                Files.exists(baselinePath) ? ConformanceReportWriter.parseBaseline(baselinePath) : List.<String>of();
        log.info(
                "[conformance] {} {}: executed {}, listed {}, judged {}",
                getClass().getSimpleName(),
                suiteName,
                executed.size(),
                listed.size(),
                judged.size());

        var coverage = dynamicTest(
                "every listed scenario ran",
                () -> assertThat(results.keySet()).as("executed scenarios").containsAll(listed.keySet()));
        var verdicts = judged.stream()
                .map(name -> dynamicTest(name, () -> verdict(name, results.get(name), baseline, outputDir)));
        return Stream.concat(Stream.of(coverage), verdicts);
    }

    private static void verdict(String scenario, @Nullable ScenarioResult result, List<String> baseline, Path outputDir)
            throws IOException {
        assertThat(result).as("Scenario %s did not run", scenario).isNotNull();
        var expectedFailure = baseline.contains(scenario);
        if (result.failed() == 0) {
            assertThat(expectedFailure)
                    .as("Scenario %s passes: remove it from the baseline", scenario)
                    .isFalse();
            return;
        }
        if (expectedFailure) {
            abort("Expected failure (baseline): " + scenario);
        }
        fail("Scenario " + scenario + ": " + result.failed() + " failed, " + result.passed() + " passed\n"
                + ConformanceReportWriter.failedChecks(outputDir, scenario));
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var paths = Files.walk(dir)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
