/*
 * Copyright (c) 2026 Konstantin Pavlov and contributors.
 */

import dev.tachyonmcp.api.server.config.Mode;
import dev.tachyonmcp.api.server.config.RuntimeConfig;
import dev.tachyonmcp.api.server.features.tasks.TaskConnector;
import dev.tachyonmcp.api.server.session.SessionIdGenerator;
import dev.tachyonmcp.core.server.config.CapabilitiesConfig;
import dev.tachyonmcp.core.server.config.FeatureConfig;
import dev.tachyonmcp.core.server.config.NetworkConfig;
import dev.tachyonmcp.core.server.config.ResourcesConfig;
import dev.tachyonmcp.core.server.config.SessionConfig;
import dev.tachyonmcp.core.server.config.TasksConfig;
import dev.tachyonmcp.core.transport.netty.NettyIoEngine;

import java.time.Clock;
import java.time.Duration;

/**
 * Configuration builder usage. These builders are passed to
 * TachyonServer.builder().capabilities(cfg -> ...), etc.
 */
final class ConfigReference {

    /**
     * Capabilities — which MCP features to advertise. Each feature has its own config type:
     * {@link FeatureConfig} (tools/prompts), {@link ResourcesConfig}, {@link TasksConfig}.
     */
    static CapabilitiesConfig capabilities() {
        // Modern Tasks requires get/cancel/update. Legacy list/awaitResult remain optional.
        var taskConnector = TaskConnector.builder()
            .get((ctx, request) -> null)
            .cancel((ctx, request) -> {})
            .update((ctx, request) -> {})
            .build();

        return CapabilitiesConfig.builder()
            .tools(FeatureConfig.builder()
                .mode(Mode.AUTO) // ON when tools registered
                .listChanged(false)
                .build())
            .resources(ResourcesConfig.builder()
                .mode(Mode.AUTO) // ON when resources registered
                .subscribe(false)
                .listChanged(false)
                .build())
            .prompts(FeatureConfig.builder()
                .mode(Mode.AUTO) // ON when prompts registered
                .listChanged(false)
                .build())
            .tasks(TasksConfig.builder()
                .enabled(false) // also advertised when a registered tool supports task augmentation
                .connector(taskConnector) // required when enabled
                .build())
            .completions(Mode.AUTO)
            .logging(false)
            .build();
    }

    /**
     * Flat setters and convenience defaults still work — they delegate to the nested configs
     * above and accumulate across chained calls (e.g. {@code c.tools().toolsPageSize(2)}).
     */
    static CapabilitiesConfig convenienceDefaults() {
        return CapabilitiesConfig.builder()
            .tools(true) // Mode.ON, listChanged=true
            .resources(true, true) // Mode.ON, subscribe=true, listChanged=true
            .prompts() // Mode.ON, listChanged=false; tasks remain off by default
            .completions() // true
            .logging() // true
            .build();
    }

    /**
     * Network — binding and transport config.
     */
    static NetworkConfig network() {
        return NetworkConfig.builder()
            .host("127.0.0.1")
            .port(8080)
            .endpointPath("/mcp")
            // Closes a connection with no INBOUND bytes for this long. A client awaiting a reply
            // sends none, so a tool slower than this is reaped mid-compute — keep long tools alive
            // by emitting progress (upgrades POST → SSE), not by inflating this. Size for dead-peer
            // detection. Must stay > heartbeatInterval.
            .readerIdleTimeout(Duration.ofSeconds(60))
            .writerIdleTimeout(Duration.ofMinutes(2))
            // Once a POST upgrades to SSE (handler emits progress), a scheduler sends `:` heartbeats
            // at this interval so the stream never looks idle. Keep < readerIdleTimeout. <= 0 disables.
            .heartbeatInterval(Duration.ofSeconds(15))
            .maxContentLength(65536) // 64KB
            .maxPipelinedRequests(16) // HTTP/1.1 requests queued behind the one in flight; over => 429 + close
            .allowedOrigins("https://app.example.com") // serialized origin: no path, no "*", no "null"
            .allowPrivateNetworks(true)
            .allowedHeaders("X-Custom-Header")
            .ioEngine(NettyIoEngine.AUTO) // io_uring > epoll > kqueue > NIO; native jars optional
            .build();
    }

    /**
     * Session — connection lifecycle.
     */
    static SessionConfig session() {
        return SessionConfig.builder()
            // stateless by default; configuring any option below enables sessions
            .sessionIdGenerator(SessionIdGenerator.DEFAULT)
            .sessionTtl(Duration.ofMinutes(10))
            .janitorInterval(Duration.ofSeconds(5))
            .build();
    }

    /**
     * Runtime — handler-execution lifecycle.
     */
    static RuntimeConfig runtime() {
        return RuntimeConfig.builder()
            .shutdownGracePeriod(Duration.ofSeconds(5)) // drain in-flight handlers on close; ZERO = interrupt now
            .clock(Clock.systemUTC()) // task timestamps and TTL/expiry checks; use Clock.fixed(...) in tests
            .build();
    }
}
