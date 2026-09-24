/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.cors.CorsConfig;
import io.netty.handler.codec.http.cors.CorsConfigBuilder;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link CorsDecision#of} to the header semantics of Netty's {@code CorsHandler} for one {@code
 * CorsConfig}, plus the deliberate {@code Vary: Origin} on list misses.
 */
class CorsDecisionTest {

    private static final String APP = "https://app.example.com";

    @Test
    void anyOriginWithoutCredentialsGrantsWildcardWithoutVary() {
        var response = decorate(CorsConfigBuilder.forAnyOrigin().exposeHeaders("MCP-Session-Id"), APP);

        assertThat(response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isEqualTo("*");
        assertThat(response.headers().contains(HttpHeaderNames.VARY)).isFalse();
        assertThat(response.headers().contains(HttpHeaderNames.ACCESS_CONTROL_ALLOW_CREDENTIALS))
                .isFalse();
        assertThat(response.headers().getAll(HttpHeaderNames.ACCESS_CONTROL_EXPOSE_HEADERS))
                .containsExactly("MCP-Session-Id");
    }

    @Test
    void anyOriginWithCredentialsEchoesOriginWithVary() {
        var response = decorate(CorsConfigBuilder.forAnyOrigin().allowCredentials(), APP);

        assertThat(response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isEqualTo(APP);
        assertThat(response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_CREDENTIALS))
                .isEqualTo("true");
        assertThat(response.headers().get(HttpHeaderNames.VARY)).isEqualTo("origin");
    }

    @Test
    void listedOriginIsEchoedWithVary() {
        var response = decorate(CorsConfigBuilder.forOrigins(APP).exposeHeaders("MCP-Session-Id"), APP);

        assertThat(response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isEqualTo(APP);
        assertThat(response.headers().get(HttpHeaderNames.VARY)).isEqualTo("origin");
        assertThat(response.headers().getAll(HttpHeaderNames.ACCESS_CONTROL_EXPOSE_HEADERS))
                .containsExactly("MCP-Session-Id");
        assertThat(response.headers().contains(HttpHeaderNames.ACCESS_CONTROL_ALLOW_CREDENTIALS))
                .isFalse();
    }

    @Test
    void listedOriginMatchesInCanonicalFormAndEchoesTheRequestValue() {
        var response = decorate(CorsConfigBuilder.forOrigins(APP), "HTTPS://App.Example.com:443");

        assertThat(response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isEqualTo("HTTPS://App.Example.com:443");
    }

    @Test
    void unlistedOriginGetsVaryButNoGrant() {
        var response =
                decorate(CorsConfigBuilder.forOrigins(APP).exposeHeaders("MCP-Session-Id"), "http://evil.example");

        assertThat(response.headers().contains(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isFalse();
        assertThat(response.headers().contains(HttpHeaderNames.ACCESS_CONTROL_EXPOSE_HEADERS))
                .isFalse();
        assertThat(response.headers().get(HttpHeaderNames.VARY))
                .as("with an origin list the response depends on Origin, grant or not")
                .isEqualTo("origin");
    }

    @Test
    void requestWithoutOriginGetsNoCorsHeaders() {
        assertThat(CorsDecision.of(CorsConfigBuilder.forOrigins(APP).build(), request(null)))
                .isEqualTo(CorsDecision.NONE);
        assertThat(CorsDecision.of(CorsConfigBuilder.forAnyOrigin().build(), request(null)))
                .isEqualTo(CorsDecision.NONE);
    }

    @Test
    void malformedOriginsNeverReceiveAGrantEvenWhenNettyAllowsNull() {
        for (var config : new CorsConfig[] {
            CorsConfigBuilder.forOrigins(APP)
                    .allowNullOrigin()
                    .allowCredentials()
                    .build(),
            CorsConfigBuilder.forAnyOrigin()
                    .allowNullOrigin()
                    .allowCredentials()
                    .build()
        }) {
            for (var origin : new String[] {"null", "", "*", "http://localhost/", "http://user@localhost"}) {
                var response = decorate(config, origin);
                assertThat(response.headers().contains(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN))
                        .isFalse();
                assertThat(response.headers().contains(HttpHeaderNames.ACCESS_CONTROL_ALLOW_CREDENTIALS))
                        .isFalse();
                assertThat(response.headers().contains(HttpHeaderNames.ACCESS_CONTROL_EXPOSE_HEADERS))
                        .isFalse();
            }
        }
    }

    @Test
    void disabledCorsDecoratesNothing() {
        var config = CorsConfigBuilder.forAnyOrigin().disable().build();

        assertThat(CorsDecision.of(config, request(APP))).isEqualTo(CorsDecision.NONE);
    }

    @Test
    void varyOriginKeepsOtherVaryValuesAndIsNotDuplicated() {
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().add(HttpHeaderNames.VARY, "Accept-Encoding");
        var decision = CorsDecision.of(CorsConfigBuilder.forOrigins(APP).build(), request(APP));

        decision.applyTo(response);
        decision.applyTo(response);

        assertThat(response.headers().getAll(HttpHeaderNames.VARY)).containsExactly("Accept-Encoding", "origin");
        response.release();
    }

    private static FullHttpResponse decorate(CorsConfigBuilder builder, String origin) {
        return decorate(builder.build(), origin);
    }

    private static FullHttpResponse decorate(CorsConfig config, String origin) {
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        CorsDecision.of(config, request(origin)).applyTo(response);
        response.release();
        return response;
    }

    private static HttpRequest request(@Nullable String origin) {
        var request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/mcp");
        if (origin != null) {
            request.headers().set(HttpHeaderNames.ORIGIN, origin);
        }
        request.release();
        return request;
    }
}
