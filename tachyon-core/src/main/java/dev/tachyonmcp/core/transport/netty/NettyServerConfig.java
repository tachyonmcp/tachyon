/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty;

import dev.tachyonmcp.core.server.config.NetworkConfig;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.cors.CorsConfig;
import io.netty.handler.codec.http.cors.CorsConfigBuilder;
import java.time.Duration;
import java.util.List;
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
 * @param corsConfig         CORS configuration, or {@code null} for defaults
 * @param allowedHosts       additional {@code Host} authorities the DNS-rebinding guard accepts
 *                           beyond localhost, or {@code null} for localhost-only
 * @param allowedOrigins     additional {@code Origin} values the DNS-rebinding guard accepts beyond
 *                           loopback origins ({@code "*"} = any), or {@code null} for loopback-only
 * @param allowNullOrigin    whether the DNS-rebinding guard accepts {@code Origin: null}
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
        @Nullable CorsConfig corsConfig,
        @Nullable List<String> allowedHosts,
        @Nullable List<String> allowedOrigins,
        boolean allowNullOrigin,
        NettyIoEngine ioEngine,
        @Nullable Consumer<ChannelPipeline> pipelineCustomizer) {

    /**
     * Builds a CORS configuration from the given parameters.
     *
     * <p>With no {@code allowedOrigins} (or a {@code "*"} entry) the configuration accepts any origin:
     * the DNS-rebinding guard ahead of the CORS handler already admits only loopback origins (on any
     * port) and allowlisted ones, so CORS need not repeat that check — and cannot, as it matches
     * origins exactly and a loopback page's origin carries its port.
     */
    public static CorsConfig buildCorsConfig(
            @Nullable List<String> allowedOrigins,
            boolean allowNullOrigin,
            boolean allowPrivateNetworks,
            @Nullable List<String> allowedHeaders) {
        var builder = allowedOrigins == null || allowedOrigins.contains("*")
                ? CorsConfigBuilder.forAnyOrigin()
                : CorsConfigBuilder.forOrigins(allowedOrigins.toArray(String[]::new));
        if (allowNullOrigin) {
            builder.allowNullOrigin();
        }
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
                buildCorsConfig(null, false, false, null),
                null,
                null,
                false,
                NettyIoEngine.AUTO,
                null);
    }
}
