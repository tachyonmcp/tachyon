/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty;

import dev.tachyonmcp.core.protocol.mcp.McpHeaderNames;
import dev.tachyonmcp.core.server.config.NetworkConfig;
import dev.tachyonmcp.core.transport.netty.http.Origins;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.cors.CorsConfig;
import io.netty.handler.codec.http.cors.CorsConfigBuilder;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Configuration for the Netty transport.
 *
 * @param host               the host address to bind to
 * @param port               the port to bind to
 * @param endpointPath       the HTTP endpoint path for MCP messages
 * @param readerIdleTimeout  idle timeout for reading
 * @param writerIdleTimeout  idle timeout for writing
 * @param maxContentLength   maximum HTTP content length in bytes
 * @param corsConfig         CORS configuration; also the origin allowlist of the DNS-rebinding guard.
 *                           A finite origin list must hold canonical serialized origins, as
 *                           {@link #buildCorsConfig} stores them, else {@link IllegalArgumentException}.
 *                           See {@link #defaultCorsConfig()}
 * @param allowedHosts       additional {@code Host} authorities the DNS-rebinding guard accepts
 *                           beyond localhost, or {@code null} for localhost-only
 * @param ioEngine           the Netty I/O engine to use
 * @param pipelineCustomizer optional customizer for the Netty channel pipeline
 */
public record NettyServerConfig(
        String host,
        int port,
        String endpointPath,
        Duration readerIdleTimeout,
        Duration writerIdleTimeout,
        int maxContentLength,
        CorsConfig corsConfig,
        @Nullable List<String> allowedHosts,
        NettyIoEngine ioEngine,
        @Nullable Consumer<ChannelPipeline> pipelineCustomizer) {

    /** Methods a browser MCP client uses: POST for messages, GET for the SSE stream, DELETE to end a session. */
    private static final HttpMethod[] ALLOWED_METHODS = {HttpMethod.GET, HttpMethod.POST, HttpMethod.DELETE};

    /**
     * Request headers every browser MCP client may send. The SEP-2243 {@code Mcp-Param-*} mirrors,
     * whose names depend on the tool, are granted per preflight by {@code CorsPreflightHandler}.
     */
    private static final String[] ALLOWED_HEADERS = {
        HttpHeaderNames.CONTENT_TYPE.toString(),
        HttpHeaderNames.AUTHORIZATION.toString(),
        McpHeaderNames.MCP_PROTOCOL_VERSION,
        McpHeaderNames.MCP_SESSION_ID,
        McpHeaderNames.LAST_EVENT_ID,
        McpHeaderNames.MCP_METHOD,
        McpHeaderNames.MCP_NAME
    };

    /** Response headers a browser MCP client must read from script. */
    private static final String[] EXPOSED_HEADERS = {McpHeaderNames.MCP_SESSION_ID, McpHeaderNames.MCP_PROTOCOL_VERSION
    };

    /** Seconds a browser may cache a preflight; browsers cap it lower (Chromium: 2h). */
    private static final long PREFLIGHT_MAX_AGE_SECONDS = 86_400;

    public NettyServerConfig {
        Objects.requireNonNull(corsConfig, "corsConfig");
        if (!corsConfig.isAnyOriginSupported()) {
            for (var origin : corsConfig.origins()) {
                if (!origin.equals(Origins.canonical(origin))) {
                    throw new IllegalArgumentException("corsConfig origin must be a canonical serialized origin, "
                            + "http(s)://host[:port] as buildCorsConfig stores it: '" + origin + "'");
                }
            }
        }
    }

    /**
     * Returns the default CORS configuration: any loopback origin the DNS-rebinding guard admits, answered
     * with {@code Access-Control-Allow-Origin: *}. Same as {@link #buildCorsConfig} with no options.
     *
     * @return the default CORS configuration
     */
    public static CorsConfig defaultCorsConfig() {
        return buildCorsConfig(null, false, null);
    }

    /**
     * Builds a CORS configuration from the given parameters.
     *
     * <p>Without {@code allowedOrigins} every origin is granted, answered with {@code
     * Access-Control-Allow-Origin: *}: the DNS-rebinding guard ahead of CORS admits only loopback
     * origins, on any port, so a browser page on a dev server such as {@code localhost:5173} works.
     * Credentials are never allowed. The preflight grants the methods and MCP request headers a
     * browser client sends, plus {@code allowedHeaders}; the transport additionally
     * grants each {@code Mcp-Param-*} header a preflight requests. Responses expose {@code MCP-Session-Id}
     * and {@code MCP-Protocol-Version} to script.
     *
     * @param allowedOrigins       origins to grant, or {@code null} for any origin the guard admits;
     *                             each a serialized origin, stored canonical
     * @param allowPrivateNetworks whether to answer Private Network Access preflights
     * @param allowedHeaders       request headers to grant beyond the built-in MCP ones
     * @return the CORS configuration
     * @throws IllegalArgumentException if an {@code allowedOrigins} entry is not a serialized origin
     */
    public static CorsConfig buildCorsConfig(
            @Nullable List<String> allowedOrigins,
            boolean allowPrivateNetworks,
            @Nullable List<String> allowedHeaders) {
        final var builder = allowedOrigins != null
                ? CorsConfigBuilder.forOrigins(
                        allowedOrigins.stream().map(Origins::requireConfigured).toArray(String[]::new))
                : CorsConfigBuilder.forAnyOrigin();
        builder.allowedRequestMethods(ALLOWED_METHODS)
                .allowedRequestHeaders(ALLOWED_HEADERS)
                .exposeHeaders(EXPOSED_HEADERS)
                .maxAge(PREFLIGHT_MAX_AGE_SECONDS);
        if (allowPrivateNetworks) {
            builder.allowPrivateNetwork();
        }
        if (allowedHeaders != null && !allowedHeaders.isEmpty()) {
            builder.allowedRequestHeaders(allowedHeaders.toArray(String[]::new));
        }
        return builder.build();
    }

    static NettyServerConfig defaults(int port) {
        return defaults(NetworkConfig.DEFAULT_HOST, port);
    }

    static NettyServerConfig defaults(String host, int port) {
        return new NettyServerConfig(
                host,
                port,
                NetworkConfig.DEFAULT_ENDPOINT_PATH,
                NetworkConfig.DEFAULT_READER_IDLE_TIMEOUT,
                NetworkConfig.DEFAULT_WRITER_IDLE_TIMEOUT,
                McpChannelInitializer.DEFAULT_MAX_CONTENT_LENGTH,
                defaultCorsConfig(),
                null,
                NettyIoEngine.AUTO,
                null);
    }
}
