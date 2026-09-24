/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tachyonmcp.core.transport.netty.McpChannelInitializer;
import dev.tachyonmcp.core.transport.netty.NettyIoEngine;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies {@link NetworkConfig} defaults, builder validation, and immutability of collection
 * fields — a built config must never expose a mutable collection to callers.
 *
 * @author Konstantin Pavlov
 */
class NetworkConfigTest {

    @Test
    void usesDefaults() {
        var config = NetworkConfig.builder().build();

        assertThat(config.host()).isEqualTo("127.0.0.1");
        assertThat(config.port()).isEqualTo(-1);
        assertThat(config.endpointPath()).isEqualTo("/mcp");
        assertThat(config.readerIdleTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(config.writerIdleTimeout()).isEqualTo(Duration.ofMinutes(5));
        assertThat(config.maxContentLength()).isEqualTo(McpChannelInitializer.DEFAULT_MAX_CONTENT_LENGTH);
        assertThat(config.allowedOrigins()).isNull();
        assertThat(config.allowPrivateNetworks()).isFalse();
        assertThat(config.allowedHeaders()).isNull();
        assertThat(config.ioEngine()).isEqualTo(NettyIoEngine.AUTO);
        assertThat(config.heartbeatInterval()).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    void allowedOriginsViaBuilderIsUnmodifiable() {
        var config = NetworkConfig.builder()
                .allowedOrigins("http://localhost", "http://127.0.0.1")
                .build();

        assertThat(config.allowedOrigins()).containsExactly("http://localhost", "http://127.0.0.1");
        assertThatThrownBy(() -> config.allowedOrigins().add("http://evil.com"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void allowedOriginsAreStoredCanonical() {
        var config = NetworkConfig.builder()
                .allowedOrigins(
                        "HTTPS://App.Example.com:443",
                        "http://Localhost:80",
                        "http://[::1]:3000",
                        "http://[2001:0db8:0:0:0:0:0:1]:80")
                .build();

        assertThat(config.allowedOrigins())
                .containsExactly(
                        "https://app.example.com", "http://localhost", "http://[::1]:3000", "http://[2001:db8::1]");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://localhost/",
                "http://localhost/path",
                "null",
                "*",
                "",
                " ",
                "ftp://x",
                "http://user@x",
                "https://x:0",
                "https://x:65536",
                "https://x:",
                "https://x?q",
                "https://x#f",
                "x.example.com",
                "http://[::1%lo]",
                "http://[::1%25lo]"
            })
    void rejectsAllowedOriginThatIsNotASerializedOrigin(String origin) {
        var builder = NetworkConfig.builder().allowedOrigins(origin);

        assertThatIllegalArgumentException()
                .isThrownBy(builder::build)
                .withMessageStartingWith("allowedOrigins entry must be a serialized origin")
                .withMessageContaining("'" + origin + "'");
    }

    @Test
    void allowedHeadersViaBuilderIsUnmodifiable() {
        var config = NetworkConfig.builder().allowedHeaders("X-Custom").build();

        assertThat(config.allowedHeaders()).containsExactly("X-Custom");
        assertThatThrownBy(() -> config.allowedHeaders().add("X-Evil"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void directConstructionWithMutableListIsDefended() {
        var origins = new ArrayList<>(List.of("http://example.com"));
        var headers = new ArrayList<>(List.of("X-Foo"));
        var hosts = new ArrayList<>(List.of("host.docker.internal:8096"));
        var config = new NetworkConfig(
                "127.0.0.1",
                8080,
                "/mcp",
                Duration.ofSeconds(60),
                Duration.ofMinutes(5),
                McpChannelInitializer.DEFAULT_MAX_CONTENT_LENGTH,
                origins,
                false,
                headers,
                hosts,
                NettyIoEngine.AUTO,
                Duration.ofSeconds(15));

        // Mutating the original lists must not affect the config
        origins.add("http://evil.com");
        headers.add("X-Evil");
        hosts.add("evil.example:1");

        assertThat(config.allowedOrigins()).containsExactly("http://example.com");
        assertThat(config.allowedHeaders()).containsExactly("X-Foo");
        assertThat(config.allowedHosts()).containsExactly("host.docker.internal:8096");
    }

    @Test
    void rejectsNonPositiveMaxContentLength() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> NetworkConfig.builder().maxContentLength(0))
                .withMessage("maxContentLength must be positive");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> NetworkConfig.builder().maxContentLength(-1))
                .withMessage("maxContentLength must be positive");
    }

    @Test
    void rejectsNullTimingArguments() {
        assertThatNullPointerException()
                .isThrownBy(() -> NetworkConfig.builder().readerIdleTimeout(null));
        assertThatNullPointerException()
                .isThrownBy(() -> NetworkConfig.builder().writerIdleTimeout(null));
        assertThatNullPointerException()
                .isThrownBy(() -> NetworkConfig.builder().heartbeatInterval(null));
        assertThatNullPointerException()
                .isThrownBy(() -> NetworkConfig.builder().ioEngine(null));
    }

    @Test
    void buildsDistinctConfigs() {
        var config1 = NetworkConfig.builder().port(8080).build();
        var config2 = NetworkConfig.builder().port(9090).build();

        assertThat(config1).isNotSameAs(config2);
        assertThat(config1.port()).isEqualTo(8080);
        assertThat(config2.port()).isEqualTo(9090);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/mcp", "/mcp/", "/api/v1/mcp", "/mcp-server_1.0~x"})
    void acceptsEndpointPath(String path) {
        assertThat(NetworkConfig.builder().endpointPath(path).build().endpointPath())
                .isEqualTo(path);
    }

    /** A path the endpoint validator can never match would 404 every request without a word. */
    @ParameterizedTest
    @ValueSource(strings = {"", "mcp", " /mcp", "/mcp ", "/m cp", "/mcp?x=1", "/mcp#top", "/mcp\t", "/mcp\u0000"})
    void rejectsUnservableEndpointPath(String path) {
        var builder = NetworkConfig.builder().endpointPath(path);

        assertThatIllegalArgumentException()
                .isThrownBy(builder::build)
                .withMessage("endpointPath must start with '/' and contain no query, fragment,"
                        + " whitespace or control characters");
    }

    @Test
    void rejectsNullEndpointPath() {
        var builder = NetworkConfig.builder().endpointPath(null);

        assertThatNullPointerException().isThrownBy(builder::build).withMessage("endpointPath");
    }
}
