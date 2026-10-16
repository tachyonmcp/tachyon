/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty.http;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.core.server.config.BearerConfig;
import dev.tachyonmcp.core.server.security.VerifiedToken;
import dev.tachyonmcp.core.transport.netty.NettyServerConfig;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * RFC 9728 §3: the metadata is a {@code GET} resource at the well-known URI, answered without
 * credentials. RFC 9110 §15.5.6: a {@code 405} lists the allowed methods.
 */
class ProtectedResourceMetadataHandlerTest {

    private static final URI RESOURCE = URI.create("https://mcp.example.com/mcp");

    // language=json
    private static final String DOCUMENT = """
            {"resource":"https://mcp.example.com/mcp","authorization_servers":["https://auth.example.com"],"scopes_supported":["tools:read"],"bearer_methods_supported":["header"]}""";

    private EmbeddedChannel channel;

    @BeforeEach
    void startChannel() {
        final var bearer = BearerConfig.builder()
                .resource(RESOURCE)
                .authorizationServers(URI.create("https://auth.example.com"))
                .scopesSupported("tools:read")
                .verifier(token -> new VerifiedToken(() -> "alice", Set.of(), Instant.MAX, Set.of(RESOURCE)))
                .build();
        channel = new EmbeddedChannel(
                new ProtectedResourceMetadataHandler(bearer),
                new TachyonCorsHandler(NettyServerConfig.defaultCorsConfig()));
    }

    @AfterEach
    void closeChannel() {
        channel.finishAndReleaseAll();
    }

    private static DefaultFullHttpRequest request(HttpMethod method, String uri) {
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, uri, Unpooled.buffer(0));
    }

    private FullHttpResponse answer(HttpMethod method, String uri, String... headers) {
        final var request = request(method, uri);
        for (int i = 0; i < headers.length; i += 2) {
            request.headers().set(headers[i], headers[i + 1]);
        }
        assertThat(channel.writeInbound(request))
                .as("the request is consumed, not forwarded")
                .isFalse();
        assertThat(request.refCnt()).isZero();
        return channel.readOutbound();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/.well-known/oauth-protected-resource/mcp",
                "/.well-known/oauth-protected-resource",
                "/.well-known/oauth-protected-resource/mcp/",
                "/.well-known/oauth-protected-resource/mcp?probe=1"
            })
    void servesTheDocumentToAnyoneOnBothWellKnownPaths(String uri) {
        final var response = answer(HttpMethod.GET, uri);

        try {
            assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
            assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE)).isEqualTo("application/json");
            assertThat(response.headers().get(HttpHeaderNames.CONTENT_LENGTH))
                    .isEqualTo(String.valueOf(DOCUMENT.length()));
            assertThat(response.headers().contains(HttpHeaderNames.CONNECTION)).isFalse();
            assertThat(response.content().toString(StandardCharsets.UTF_8)).isEqualTo(DOCUMENT);
            assertThat(response.headers().contains(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN))
                    .as("no Origin, no CORS grant")
                    .isFalse();
            assertThat(channel.isActive()).isTrue();
        } finally {
            response.release();
        }
    }

    @Test
    void everyAnswerIsAFreshViewOfTheSameDocument() {
        for (int i = 0; i < 3; i++) {
            final var response = answer(HttpMethod.GET, "/.well-known/oauth-protected-resource/mcp");
            assertThat(response.content().toString(StandardCharsets.UTF_8)).isEqualTo(DOCUMENT);
            response.release();
        }
    }

    @Test
    void aBrowserPageOnALoopbackOriginCanReadItAndSeeWwwAuthenticate() {
        final var response =
                answer(HttpMethod.GET, "/.well-known/oauth-protected-resource/mcp", "Origin", "http://localhost:5173");

        try {
            assertThat(response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN))
                    .isEqualTo("*");
            assertThat(response.headers().getAll(HttpHeaderNames.ACCESS_CONTROL_EXPOSE_HEADERS))
                    .flatExtracting(value -> List.of(value.split(",\\s*")))
                    .map(String::toLowerCase)
                    .contains("www-authenticate");
        } finally {
            response.release();
        }
    }

    @Test
    void closesAfterTheAnswerWhenTheClientAsked() {
        final var response = answer(HttpMethod.GET, "/.well-known/oauth-protected-resource/mcp", "Connection", "close");

        try {
            assertThat(response.headers().get(HttpHeaderNames.CONNECTION)).isEqualTo("close");
        } finally {
            response.release();
        }
    }

    /** RFC 9110 §9.1: a general-purpose server supports {@code HEAD}; §9.3.2: the headers of the {@code GET}, no body. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "/.well-known/oauth-protected-resource/mcp",
                "/.well-known/oauth-protected-resource",
            })
    void answersHeadWithTheHeadersOfGetAndNoBody(String uri) {
        final var get = answer(HttpMethod.GET, uri);
        final var head = answer(HttpMethod.HEAD, uri, "Origin", "http://localhost:5173");

        try {
            assertThat(head.status()).isEqualTo(HttpResponseStatus.OK);
            assertThat(head.headers().get(HttpHeaderNames.CONTENT_TYPE)).isEqualTo("application/json");
            assertThat(head.headers().get(HttpHeaderNames.CONTENT_LENGTH))
                    .isEqualTo(get.headers().get(HttpHeaderNames.CONTENT_LENGTH))
                    .isEqualTo(String.valueOf(DOCUMENT.length()));
            assertThat(head.content().readableBytes()).isZero();
            assertThat(head.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN))
                    .isEqualTo("*");
            assertThat(get.content().toString(StandardCharsets.UTF_8)).isEqualTo(DOCUMENT);
        } finally {
            get.release();
            head.release();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE", "OPTIONS"})
    void refusesEveryMethodButGetAndHeadAndSaysWhichIsAllowed(String method) {
        final var response = answer(HttpMethod.valueOf(method), "/.well-known/oauth-protected-resource/mcp");

        try {
            assertThat(response.status()).isEqualTo(HttpResponseStatus.METHOD_NOT_ALLOWED);
            assertThat(response.headers().get(HttpHeaderNames.ALLOW)).isEqualTo("GET, HEAD");
            assertThat(response.headers().get(HttpHeaderNames.CONTENT_LENGTH)).isEqualTo("0");
            assertThat(response.headers().get(HttpHeaderNames.CONNECTION)).isEqualTo("close");
            assertThat(response.content().readableBytes()).isZero();
        } finally {
            response.release();
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/mcp",
                "/",
                "/.well-known/oauth-protected-resource/other",
                "/.well-known/oauth-protected-resource/mcp/extra",
                "/.well-known/oauth-protected-resourcemcp",
                "/.well-known/oauth-authorization-server",
                "/.well-known/oauth-authorization-server/mcp"
            })
    void leavesEveryOtherPathToTheRestOfThePipeline(String uri) {
        final var request = request(HttpMethod.POST, uri);

        assertThat(channel.writeInbound(request)).isTrue();

        final HttpRequest forwarded = channel.readInbound();
        assertThat(forwarded).isSameAs(request);
        assertThat(channel.<Object>readOutbound()).isNull();
        ReferenceCountUtil.release(forwarded);
    }

    @Test
    void leavesTheBodyOfARequestItDoesNotAnswerAlone() {
        assertThat(channel.writeInbound(LastHttpContent.EMPTY_LAST_CONTENT)).isTrue();

        assertThat(channel.<Object>readInbound()).isSameAs(LastHttpContent.EMPTY_LAST_CONTENT);
    }
}
