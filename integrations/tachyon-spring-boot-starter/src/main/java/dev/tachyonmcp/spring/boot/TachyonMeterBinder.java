/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.spring.boot;

import dev.tachyonmcp.core.server.TachyonServer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
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
        bindTo(registry);
    }

    void bindTo(MeterRegistry registry) {
        Gauge.builder("mcp.server.tools", server, s -> s.tools().descriptors().size())
                .description("Registered MCP tools")
                .register(registry);
        Gauge.builder(
                        "mcp.server.prompts",
                        server,
                        s -> s.prompts().descriptors().size())
                .description("Registered MCP prompts")
                .register(registry);
        Gauge.builder(
                        "mcp.server.resources",
                        server,
                        s -> s.resources().descriptors().size()
                                + s.resources().templateDescriptors().size())
                .description("Registered MCP resources and resource templates")
                .register(registry);
    }
}
