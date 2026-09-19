/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.spring.boot;

import dev.tachyonmcp.core.server.TachyonServer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.ToDoubleFunction;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * Gauges registered MCP features of a {@link TachyonServer}.
 *
 * <p>Not a {@code MeterBinder}: Boot binds those from the optional
 * {@code spring-boot-micrometer-metrics} module, and binding during {@code MeterRegistry} creation
 * would cycle back through {@code tachyonMetricsCustomizer}, which the server depends on.
 */
final class TachyonMeterBinder implements SmartInitializingSingleton {

    private final TachyonServer server;
    private final MeterRegistry registry;

    TachyonMeterBinder(TachyonServer server, MeterRegistry registry) {
        this.server = server;
        this.registry = registry;
    }

    @Override
    public void afterSingletonsInstantiated() {
        bindGauge(
                "mcp.server.tools",
                "Registered MCP tools",
                s -> s.tools().descriptors().size());
        bindGauge(
                "mcp.server.prompts",
                "Registered MCP prompts",
                s -> s.prompts().descriptors().size());
        bindGauge(
                "mcp.server.resources",
                "Registered MCP resources and resource templates",
                s -> s.resources().descriptors().size()
                        + s.resources().templateDescriptors().size());
    }

    private void bindGauge(String name, String description, ToDoubleFunction<TachyonServer> value) {
        Gauge.builder(name, server, value).description(description).register(registry);
    }
}
