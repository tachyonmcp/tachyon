/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.tachyonmcp.core.transport.netty.http.CorsDecision;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.cors.CorsConfig;
import io.netty.handler.codec.http.cors.CorsConfigBuilder;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Pins the canonical-origin contract {@link NettyServerConfig} enforces on any supplied {@code CorsConfig}. */
class NettyServerConfigTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://App.Example.com",
                "HTTPS://app.example.com",
                "https://app.example.com:443",
                "http://app.example.com:80",
                "https://app.example.com/",
                "http://[0:0:0:0:0:0:0:1]:8080"
            })
    void rejectsNonCanonicalOriginInSuppliedCorsConfig(String origin) {
        var cors = CorsConfigBuilder.forOrigins(origin).build();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> withCors(cors))
                .withMessageContaining("canonical serialized origin")
                .withMessageContaining("'" + origin + "'");
    }

    @ParameterizedTest
    @CsvSource({
        "https://app.example.com, HTTPS://App.Example.com:443",
        "http://[::1]:8080,       http://[0:0:0:0:0:0:0:1]:8080",
        "http://localhost:3000,   http://LOCALHOST:3000"
    })
    void canonicalOriginIsAcceptedAndGrantsEquivalentRequestOrigin(String configured, String requestOrigin) {
        var config = withCors(CorsConfigBuilder.forOrigins(configured).build());
        var request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/mcp");
        request.headers().set(HttpHeaderNames.ORIGIN, requestOrigin);

        var decision = CorsDecision.of(config.corsConfig(), request);

        assertThat(config.corsConfig().origins()).containsExactly(configured);
        assertThat(decision.allowOrigin()).isEqualTo(requestOrigin);
        assertThat(decision.varyOrigin()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
        "https://App.Example.com:443,    https://app.example.com",
        "HTTP://Localhost:80,            http://localhost",
        "http://[0:0:0:0:0:0:0:1]:8080,  http://[::1]:8080"
    })
    void buildCorsConfigOutputIsAccepted(String allowed, String canonical) {
        var config = withCors(NettyServerConfig.buildCorsConfig(List.of(allowed), false, null));

        assertThat(config.corsConfig().isAnyOriginSupported()).isFalse();
        assertThat(config.corsConfig().origins()).containsExactly(canonical);
    }

    @Test
    void anyOriginModeIsAccepted() {
        assertThat(withCors(CorsConfigBuilder.forAnyOrigin().build())
                        .corsConfig()
                        .isAnyOriginSupported())
                .isTrue();
        assertThat(withCors(NettyServerConfig.defaultCorsConfig()).corsConfig().isAnyOriginSupported())
                .isTrue();
    }

    private static NettyServerConfig withCors(CorsConfig cors) {
        var defaults = NettyServerConfig.defaults(0);
        return new NettyServerConfig(
                defaults.host(),
                defaults.port(),
                defaults.endpointPath(),
                defaults.readerIdleTimeout(),
                defaults.writerIdleTimeout(),
                defaults.maxContentLength(),
                cors,
                defaults.allowedHosts(),
                defaults.ioEngine(),
                defaults.pipelineCustomizer());
    }
}
