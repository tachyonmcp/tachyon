/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.spring.boot;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.core.transport.netty.NettyIoEngine;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.util.unit.DataSize;

/**
 * {@code tachyon.*} configuration properties. Anything beyond these — stores, generators, clocks,
 * serializers and other wiring — goes through a {@link TachyonServerCustomizer} bean.
 *
 * <p>Nullable components are applied only when set, so Tachyon's configuration records stay the
 * single source of truth for defaults. Documented defaults live in {@code
 * META-INF/additional-spring-configuration-metadata.json}, pinned to those records by
 * {@code TachyonConfigurationMetadataTest}.
 *
 * <p>Each {@code @param} below becomes IDE completion text: plain sentences, no markup, no defaults.
 *
 * @param enabled Whether to create and start the MCP server.
 * @param name    Server name reported to clients.
 * @param version Server version reported to clients.
 * @param host    Bind address for the MCP transport.
 * @param port    Port the MCP transport listens on; 0 picks an ephemeral port. Tachyon binds its own
 *                Netty transport independently of server.port, so leaving both at 8080 in a Spring
 *                web application makes one of them fail to bind.
 * @param network Network transport settings.
 * @param session Server-side session settings.
 * @param runtime Handler-execution settings.
 */
@ExperimentalApi
@ConfigurationProperties("tachyon")
public record TachyonProperties(
        @DefaultValue("true") boolean enabled,
        @Nullable String name,
        @Nullable String version,
        @Nullable String host,
        @DefaultValue("8080") int port,
        @DefaultValue Network network,
        @DefaultValue Session session,
        @DefaultValue Runtime runtime) {

    /**
     * {@code tachyon.network.*} settings, applied to Tachyon's network configuration.
     *
     * @param endpointPath        HTTP path serving the MCP endpoints.
     * @param readerIdleTimeout   Close connections that receive no inbound traffic for this long. If
     *                            a duration suffix is not specified, seconds will be used. Size it
     *                            for dead-peer detection rather than for tool runtime; a long-running
     *                            tool is kept alive by SSE heartbeats instead.
     * @param writerIdleTimeout   Close connections that send no outbound traffic for this long. If a
     *                            duration suffix is not specified, seconds will be used.
     * @param heartbeatInterval   Interval between SSE comment heartbeats that keep an upgraded stream
     *                            alive. If a duration suffix is not specified, seconds will be used.
     *                            Keep it below the idle timeout of any proxy in front of the server
     *                            and below the session TTL. Zero disables heartbeats.
     * @param maxContentLength    Maximum size of an HTTP request body.
     * @param maxPipelinedRequests Pipelined HTTP/1.1 requests that may wait behind the one in flight on
     *                            a connection. The next one gets 429 Too Many Requests and the
     *                            connection closes. Zero disables pipelining.
     * @param maxPendingSseBytes  Encoded, unsent output one POST-SSE stream may buffer. Zero disables
     *                            buffering: a tool waits until each event reaches the socket. Past
     *                            it, a tool sending progress, logs or comments waits for the client.
     *                            The final response is always accepted.
     * @param allowedOrigins      Origins the DNS-rebinding guard admits and the CORS handler grants,
     *                            each http(s)://host[:port] with no path. Unset grants any loopback
     *                            origin, on any port.
     * @param allowedHeaders      Request headers accepted by the CORS handler, beyond the built-in
     *                            ones.
     * @param allowedHosts        Host authorities the DNS-rebinding guard accepts beyond its built-in
     *                            loopback hosts, each either a host or a host:port.
     * @param allowPrivateNetworks Whether to accept CORS preflights from the private network address
     *                            space.
     * @param ioEngine            Netty I/O engine; AUTO picks the best native transport available.
     */
    public record Network(
            @Nullable String endpointPath,
            @DurationUnit(ChronoUnit.SECONDS) @Nullable Duration readerIdleTimeout,
            @DurationUnit(ChronoUnit.SECONDS) @Nullable Duration writerIdleTimeout,
            @DurationUnit(ChronoUnit.SECONDS) @Nullable Duration heartbeatInterval,
            @Nullable DataSize maxContentLength,
            @Nullable Integer maxPipelinedRequests,
            @Nullable DataSize maxPendingSseBytes,
            @Nullable List<String> allowedOrigins,
            @Nullable List<String> allowedHeaders,
            @Nullable List<String> allowedHosts,
            @Nullable Boolean allowPrivateNetworks,
            @Nullable NettyIoEngine ioEngine) {}

    /**
     * {@code tachyon.session.*} settings. Setting any option enables sessions, mirroring the core
     * builder; {@code enabled} turns them on with defaults, or false states the stateless choice.
     *
     * @param enabled         Whether to keep server-side sessions. Setting any other option under
     *                        this group enables them too.
     * @param sessionTtl      Evict a session after it has been idle for this long. If a duration
     *                        suffix is not specified, seconds will be used.
     * @param janitorInterval Interval between sweeps that evict expired sessions. If a duration
     *                        suffix is not specified, seconds will be used.
     */
    public record Session(
            @Nullable Boolean enabled,
            @DurationUnit(ChronoUnit.SECONDS) @Nullable Duration sessionTtl,
            @DurationUnit(ChronoUnit.SECONDS) @Nullable Duration janitorInterval) {}

    /**
     * {@code tachyon.runtime.*} settings, applied to Tachyon's handler-execution configuration.
     *
     * @param shutdownGracePeriod Time in-flight handlers are given to drain on shutdown before they
     *                            are interrupted. If a duration suffix is not specified, seconds will
     *                            be used. Zero interrupts running handlers immediately.
     * @param requestTimeout      Timeout for requests the server sends to the client. If a duration
     *                            suffix is not specified, seconds will be used.
     */
    public record Runtime(
            @DurationUnit(ChronoUnit.SECONDS) @Nullable Duration shutdownGracePeriod,
            @DurationUnit(ChronoUnit.SECONDS) @Nullable Duration requestTimeout) {}
}
