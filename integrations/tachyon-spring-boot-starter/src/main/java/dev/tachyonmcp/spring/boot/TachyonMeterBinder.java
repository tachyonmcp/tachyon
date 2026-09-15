/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.spring.boot;

import dev.tachyonmcp.core.server.TachyonServer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

/** Gauges registered MCP features of a {@link TachyonServer}. */
final class TachyonMeterBinder implements MeterBinder {

    private final TachyonServer server;

    TachyonMeterBinder(TachyonServer server) {
        this.server = server;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
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
