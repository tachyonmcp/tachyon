/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.spring.boot;

import static dev.tachyonmcp.testkit.JsonRpcResponseAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.awaitility.Awaitility.await;

import dev.tachyonmcp.api.annotations.McpTool;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.server.observability.OperationOutcome;
import dev.tachyonmcp.testkit.McpTestClients;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class TachyonActuatorAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TachyonAutoConfiguration.class))
            .withPropertyValues("tachyon.port=0", "tachyon.host=127.0.0.1");

    static class Greeter {
        @McpTool
        String greet(String name) {
            return "Hello, " + name;
        }
    }

    @Test
    void healthFollowsLifecycleState() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            final var server = context.getBean(TachyonServer.class);
            final var indicator = context.getBean("tachyonHealthIndicator", HealthIndicator.class);

            final var up = indicator.health();
            assertThat(up.getStatus()).isEqualTo(Status.UP);
            assertThat(up.getDetails()).containsEntry("host", server.host()).containsEntry("port", server.port());

            context.getBean(TachyonServerLifecycle.class).stop();
            final var down = indicator.health();
            assertThat(down.getStatus()).isEqualTo(Status.DOWN);
            assertThat(down.getDetails())
                    .as("a bare DOWN cannot be acted on; shut down and still starting differ")
                    .containsExactly(entry("state", "stopped"));
        });
    }

    @Test
    void healthDistinguishesAServerThatNeverStartedFromOneThatStopped() {
        try (var server = TachyonServer.builder().port(0).build()) {
            final var lifecycle = new TachyonServerLifecycle(server);
            final var indicator = new TachyonHealthIndicator(server, lifecycle);

            final var beforeStart = indicator.health();
            assertThat(beforeStart.getStatus()).isEqualTo(Status.DOWN);
            assertThat(beforeStart.getDetails()).containsExactly(entry("state", "not-started"));

            lifecycle.start();
            assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);

            lifecycle.stop();
            assertThat(indicator.health().getDetails()).containsExactly(entry("state", "stopped"));
        }
    }

    @Test
    void healthBacksOffWhenDisabledOrHealthModuleAbsent() {
        runner.withPropertyValues("management.health.tachyon.enabled=false").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean("tachyonHealthIndicator");
            assertThat(context.getBean(TachyonServerLifecycle.class).isRunning())
                    .isTrue();
        });
        runner.withClassLoader(new FilteredClassLoader(HealthIndicator.class)).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean("tachyonHealthIndicator");
            assertThat(context.getBean(TachyonServerLifecycle.class).isRunning())
                    .isTrue();
        });
    }

    @Test
    void metricsTimeOperationsAndGaugeRegisteredFeatures() {
        assertMetrics(runner.withConfiguration(AutoConfigurations.of(MetricsAutoConfiguration.class)));
    }

    @Test
    void metricsWorkWithoutBootMetricsModule() {
        assertMetrics(runner.withClassLoader(new FilteredClassLoader("org.springframework.boot.micrometer.metrics")));
    }

    private void assertMetrics(ApplicationContextRunner metricsRunner) {
        metricsRunner
                .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(Greeter.class)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(TachyonServerCustomizer.class);
                    final var registry = context.getBean(SimpleMeterRegistry.class);
                    final var server = context.getBean(TachyonServer.class);
                    try (var client = McpTestClients.latest(server.port())) {
                        // language=json
                        assertThat(client.post("""
                                {"jsonrpc":"2.0","id":1,"method":"tools/call",
                                 "params":{"name":"greet","arguments":{"name":"Ada"}}}
                                """)).isSuccess().hasId(1);
                    }

                    await().untilAsserted(() -> assertThat(registry.find(TachyonMetricsListener.OPERATIONS)
                                    .tag("mcp.method.name", "tools/call")
                                    .tag("outcome", "completed")
                                    .timer())
                            .isNotNull()
                            .satisfies(timer -> assertThat(timer.count()).isEqualTo(1)));
                    assertThat(registry.get("mcp.server.tools").gauge().value()).isEqualTo(1.0);
                    assertThat(registry.get("mcp.server.prompts").gauge().value())
                            .isZero();
                    assertThat(registry.get("mcp.server.resources").gauge().value())
                            .isZero();
                });
    }

    /**
     * The dispatcher builds its {@code OperationInfo} from the raw wire method before resolving a
     * handler, so tagging it verbatim would let a client mint a meter per bogus method name and grow
     * the registry without bound.
     */
    @Test
    void unknownMethodsShareOneTagInsteadOfMintingAMeterEach() {
        runner.withConfiguration(AutoConfigurations.of(MetricsAutoConfiguration.class))
                .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(Greeter.class)
                .run(context -> {
                    final var registry = context.getBean(SimpleMeterRegistry.class);
                    final var server = context.getBean(TachyonServer.class);
                    try (var client = McpTestClients.latest(server.port())) {
                        for (var i = 0; i < 5; i++) {
                            // language=json
                            client.post("""
                                    {"jsonrpc":"2.0","id":%d,"method":"attack/%d"}
                                    """.formatted(i, i));
                        }
                    }

                    await().untilAsserted(() -> assertThat(registry.find(TachyonMetricsListener.OPERATIONS)
                                    .tag("mcp.method.name", TachyonMetricsListener.UNKNOWN_METHOD)
                                    .timer())
                            .isNotNull()
                            .satisfies(timer -> assertThat(timer.count()).isEqualTo(5)));
                    assertThat(registry.find(TachyonMetricsListener.OPERATIONS).timers())
                            .as("five bogus methods must not become five meters")
                            .hasSize(1);
                });
    }

    /**
     * {@code TachyonServerCustomizer} is a plural SPI, so the metrics customizer must back off by
     * bean name. A type-based condition would let any unrelated customizer in the application switch
     * MCP metrics off — silently, since customizers are collected, not replaced.
     */
    @Test
    void anUnrelatedCustomizerDoesNotDisableMetrics() {
        runner.withConfiguration(AutoConfigurations.of(MetricsAutoConfiguration.class))
                .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(Greeter.class)
                .withBean("applicationCustomizer", TachyonServerCustomizer.class, () -> builder -> builder.version("9"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(TachyonServerCustomizer.class))
                            .containsKeys("applicationCustomizer", "tachyonMetricsCustomizer");

                    final var registry = context.getBean(SimpleMeterRegistry.class);
                    final var server = context.getBean(TachyonServer.class);
                    try (var client = McpTestClients.latest(server.port())) {
                        // language=json
                        assertThat(client.post("""
                                {"jsonrpc":"2.0","id":1,"method":"tools/call",
                                 "params":{"name":"greet","arguments":{"name":"Ada"}}}
                                """)).isSuccess().hasId(1);
                    }
                    await().untilAsserted(() -> assertThat(registry.find(TachyonMetricsListener.OPERATIONS)
                                    .timer())
                            .isNotNull());
                    assertThat(server.config().identity().version()).isEqualTo("9");
                });
    }

    /**
     * The gauges bean is declared by its own type, not as a bare {@code SmartInitializingSingleton},
     * so it can be conditioned on and replaced — and so it cannot be confused with
     * {@code TachyonBeanRegistrar}, which implements the same callback interface.
     */
    @Test
    void theGaugesBeanCarriesItsOwnTypeAndBacksOff() {
        final var replacement =
                new TachyonMeterBinder(TachyonServer.builder().port(0).build(), new SimpleMeterRegistry());

        runner.withConfiguration(AutoConfigurations.of(MetricsAutoConfiguration.class))
                .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(TachyonMeterBinder.class));

        runner.withConfiguration(AutoConfigurations.of(MetricsAutoConfiguration.class))
                .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
                .withBean("myGauges", TachyonMeterBinder.class, () -> replacement)
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(TachyonMeterBinder.class)
                        .doesNotHaveBean("tachyonMeterBinder"));
    }

    @Test
    void metricsBackOffWithoutRegistryOrMicrometer() {
        runner.withBean(Greeter.class).run(context -> {
            assertThat(context)
                    .hasNotFailed()
                    .doesNotHaveBean("tachyonMeterBinder")
                    .doesNotHaveBean(TachyonServerCustomizer.class);
            assertThat(context.getBean(TachyonServerLifecycle.class).isRunning())
                    .isTrue();
        });
        runner.withClassLoader(new FilteredClassLoader(MeterRegistry.class)).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(TachyonServerCustomizer.class);
            assertThat(context.getBean(TachyonServerLifecycle.class).isRunning())
                    .isTrue();
        });
    }

    @Test
    void outcomeTagsUseSnakeCase() {
        assertThat(TachyonMetricsListener.outcome(new OperationOutcome.Completed()))
                .isEqualTo("completed");
        assertThat(TachyonMetricsListener.outcome(new OperationOutcome.NotificationAccepted()))
                .isEqualTo("notification_accepted");
    }
}
