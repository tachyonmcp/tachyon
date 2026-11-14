/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.transport.netty.NettyServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Tag("conformance")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.CONCURRENT)
public abstract class AbstractServerConformanceTest {

    private static final Logger log = LoggerFactory.getLogger(AbstractServerConformanceTest.class);
    private final AbstractConformanceServer serverFactory;
    private final String packageSpec;
    private final String baselineFileName;
    private final String suiteName;
    private final String specVersion;
    private final boolean stateful;
    private final ServerInstance statefulServer;
    private final ServerInstance statelessServer;

    /**
     * @param serverFactory the server under test
     * @param ref the upstream conformance release ({@code 0.2.0-alpha.12}) or git commit/branch
     * @param specVersion the {@code --spec-version} upstream filters scenarios by
     * @param stateful whether scenarios run against the stateful server
     */
    protected AbstractServerConformanceTest(
            AbstractConformanceServer serverFactory, String ref, String specVersion, boolean stateful) {
        this.serverFactory = serverFactory;
        this.packageSpec = ConformanceRunner.packageSpec(ref);
        this.suiteName = getClass()
                .getPackageName()
                .substring(getClass().getPackageName().lastIndexOf('.') + 2)
                .replace('_', '-');
        this.baselineFileName = "conformance-baseline-" + suiteName + ".yml";
        this.specVersion = specVersion;
        this.stateful = stateful;
        log.info("[conformance] suite={} spec={}", suiteName, packageSpec);
        statefulServer = startServer(true);
        statelessServer = startServer(false);
    }

    @AfterAll
    void stopServers() {
        try {
            statefulServer.close();
        } finally {
            statelessServer.close();
        }
    }

    @TestFactory
    Stream<DynamicTest> serverConformance() throws Exception {
        assumeTrue(ConformanceRunner.isNpxAvailable(), "npx is not available on this system");

        var outputDir = "target/surefire-reports/" + suiteName + "-conformance-results";

        var baselinePath = Path.of(baselineFileName);
        var expectedFailures =
                Files.exists(baselinePath) ? ConformanceReportWriter.parseBaseline(baselinePath) : List.<String>of();

        var tempRunner = new ConformanceRunner("http://localhost:0/mcp", packageSpec, outputDir, baselineFileName);
        var scenarios = tempRunner.listScenarios(specVersion);

        return scenarios.stream()
                .map(scenario ->
                        dynamicTest(scenario, () -> runConformanceScenario(scenario, outputDir, expectedFailures)));
    }

    @Test
    @Disabled("Run it manually")
    void runAll() throws IOException, InterruptedException {

        var outputDir = "target/surefire-reports/" + suiteName + "-conformance-results";

        var server = serverFactory.startServer(stateful);
        try (server;
                var nettyServer = new NettyServer(0, server)) {
            var port = nettyServer.port();

            var runner = new ConformanceRunner(
                    "http://localhost:" + port + "/mcp", packageSpec, outputDir, baselineFileName);

            var result = runner.runSuite("all", specVersion);

            if (result.passed()) {
                log.info("[conformance] Suite {} ran successfully", suiteName);
            }

            System.out.println("[conformance] Result " + String.join("\n", result.outputLines()));

            if (!result.passed()) {
                fail("[conformance] Suite " + suiteName + " failed");
            }
        }
    }

    private void runConformanceScenario(String scenario, String outputDir, List<String> expectedFailures)
            throws Exception {
        var server = stateful ? statefulServer : statelessServer;
        var scenarioRunner = new ConformanceRunner(
                "http://localhost:" + server.port() + "/mcp", packageSpec, outputDir, baselineFileName);

        System.out.println("[conformance] Running: " + scenario);
        var result = scenarioRunner.runScenario(scenario, specVersion);
        var outputLines = result.outputLines();
        outputLines.forEach(line -> System.out.println("[conformance] " + line));

        var rawFile = Path.of(outputDir, suiteName + "-" + scenario + "-raw.log");
        Files.createDirectories(rawFile.getParent());
        Files.write(rawFile, outputLines);

        var parsed = ConformanceReportWriter.parseResults(outputLines);
        int failed = parsed.isEmpty() ? 0 : parsed.getFirst().failed();
        boolean processFailed = !result.passed();

        if (processFailed && parsed.isEmpty()) {
            fail("Conformance process failed for scenario " + scenario
                    + " (exit code " + result.exitCode() + ")"
                    + " and no summary was found in output");
        }

        if (failed > 0 && expectedFailures.contains(scenario)) {
            abort("Expected failure (baseline) for scenario " + scenario);
        }

        if (processFailed) {
            fail("Conformance process failed for scenario " + scenario + " (exit code " + result.exitCode() + ")");
        }

        assertThat(failed).as("Scenario %s failed unexpectedly", scenario).isEqualTo(0);
    }

    private ServerInstance startServer(boolean stateful) {
        var engine = serverFactory.startServer(stateful);
        try {
            return new ServerInstance(engine, new NettyServer(0, engine));
        } catch (RuntimeException e) {
            engine.close();
            throw e;
        }
    }

    private record ServerInstance(ServerEngine engine, NettyServer transport) implements AutoCloseable {

        int port() {
            return transport.port();
        }

        @Override
        public void close() {
            try {
                transport.close();
            } finally {
                engine.close();
            }
        }
    }
}
