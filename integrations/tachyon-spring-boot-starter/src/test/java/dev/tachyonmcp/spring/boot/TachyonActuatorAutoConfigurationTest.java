/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.spring.boot;

import static dev.tachyonmcp.testkit.JsonRpcResponseAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.tachyonmcp.api.annotations.McpTool;
import dev.tachyonmcp.core.server.TachyonServer;
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
            assertThat(down.getDetails()).isEmpty();
        });
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
        assertThat(TachyonMetricsListener.outcome(
                        new dev.tachyonmcp.core.server.observability.OperationOutcome.Completed()))
                .isEqualTo("completed");
        assertThat(TachyonMetricsListener.outcome(
                        new dev.tachyonmcp.core.server.observability.OperationOutcome.NotificationAccepted()))
                .isEqualTo("notification_accepted");
    }
}
