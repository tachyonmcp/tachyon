/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DnsRebindingProtectionHandlerTest {

    private @Nullable EmbeddedChannel channel;

    @AfterEach
    void releaseChannel() {
        if (channel != null) {
            channel.finishAndReleaseAll();
        }
    }

    private static HttpRequest requestWithHost(@Nullable String host) {
        return request(HttpVersion.HTTP_1_1, host);
    }

    private static HttpRequest request(HttpVersion version, @Nullable String host) {
        var req = new DefaultFullHttpRequest(version, HttpMethod.POST, "/mcp");
        if (host != null) {
            req.headers().set(HttpHeaderNames.HOST, host);
        }
        return req;
    }

    /** Returns the status of a rejection response written outbound (releasing it), or {@code null}. */
    private static @Nullable HttpResponseStatus rejectionStatus(EmbeddedChannel channel) {
        Object out = channel.readOutbound();
        try {
            return out instanceof HttpResponse resp ? resp.status() : null;
        } finally {
            ReferenceCountUtil.release(out);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost:8096", "localhost.:8096", "127.0.0.1:8096", "[::1]:8096"})
    void allowsLoopbackHostByDefault(String host) {
        channel = new EmbeddedChannel(new DnsRebindingProtectionHandler());
        assertThat(channel.writeInbound(requestWithHost(host)))
                .as("loopback Host must pass through")
                .isTrue();
        assertThat(rejectionStatus(channel)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost.:3000", "http://[::1]:3000"})
    void allowsLoopbackOriginByDefault(String origin) {
        channel = new EmbeddedChannel(new DnsRebindingProtectionHandler());
        var req = requestWithHost("localhost:8096");
        req.headers().set(HttpHeaderNames.ORIGIN, origin);
        assertThat(channel.writeInbound(req)).isTrue();
        assertThat(rejectionStatus(channel)).isNull();
    }

    @Test
    void allowedOriginMatchesExactly() {
        channel = new EmbeddedChannel(
                new DnsRebindingProtectionHandler(List.of(), List.of("https://app.example.com"), false));
        var allowed = requestWithHost("localhost:8096");
        allowed.headers().set(HttpHeaderNames.ORIGIN, "https://app.example.com");
        assertThat(channel.writeInbound(allowed)).isTrue();
        assertThat(rejectionStatus(channel)).isNull();

        var otherPort = requestWithHost("localhost:8096");
        otherPort.headers().set(HttpHeaderNames.ORIGIN, "https://app.example.com:8443");
        channel.writeInbound(otherPort);
        assertThat(rejectionStatus(channel)).isEqualTo(HttpResponseStatus.FORBIDDEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://anywhere.example.com", "null"})
    void wildcardOriginAdmitsAnyOriginButStillChecksHost(String origin) {
        channel = new EmbeddedChannel(new DnsRebindingProtectionHandler(List.of(), List.of("*"), false));
        var req = requestWithHost("localhost:8096");
        req.headers().set(HttpHeaderNames.ORIGIN, origin);
        assertThat(channel.writeInbound(req)).isTrue();
        assertThat(rejectionStatus(channel)).isNull();

        var rebound = requestWithHost("attacker.example.com");
        rebound.headers().set(HttpHeaderNames.ORIGIN, origin);
        channel.writeInbound(rebound);
        assertThat(rejectionStatus(channel)).isEqualTo(HttpResponseStatus.FORBIDDEN);
    }

    @Test
    void rejectsNonLocalhostHostByDefault() {
        channel = new EmbeddedChannel(new DnsRebindingProtectionHandler());
        channel.writeInbound(requestWithHost("host.docker.internal:8096"));
        assertThat(rejectionStatus(channel)).isEqualTo(HttpResponseStatus.FORBIDDEN);
    }

    @Test
    void allowsHostOnTheAllowlist() {
        channel = new EmbeddedChannel(new DnsRebindingProtectionHandler(List.of("host.docker.internal:8096")));
        assertThat(channel.writeInbound(requestWithHost("host.docker.internal:8096")))
                .as("an allowlisted Host must pass through")
                .isTrue();
        assertThat(rejectionStatus(channel)).isNull();
    }

    @Test
    void allowlistEntryWithoutPortMatchesAnyPort() {
        channel = new EmbeddedChannel(new DnsRebindingProtectionHandler(List.of("host.docker.internal")));
        assertThat(channel.writeInbound(requestWithHost("host.docker.internal:8096")))
                .isTrue();
    }

    @Test
    void allowlistIsCaseInsensitive() {
        channel = new EmbeddedChannel(new DnsRebindingProtectionHandler(List.of("Host.Docker.Internal:8096")));
        assertThat(channel.writeInbound(requestWithHost("host.docker.internal:8096")))
                .isTrue();
    }

    @Test
    void stillRejectsHostsNotOnTheAllowlist() {
        channel = new EmbeddedChannel(new DnsRebindingProtectionHandler(List.of("host.docker.internal:8096")));
        channel.writeInbound(requestWithHost("evil.host.docker.internal:8096"));
        assertThat(rejectionStatus(channel)).isEqualTo(HttpResponseStatus.FORBIDDEN);
    }

    @Test
    void allowlistMatchesBracketedIpv6WithPort() {
        channel = new EmbeddedChannel(new DnsRebindingProtectionHandler(List.of("[2001:db8::1]:8096")));
        assertThat(channel.writeInbound(requestWithHost("[2001:db8::1]:8096"))).isTrue();
    }

    @Test
    void allowlistBracketedIpv6HostOnlyMatchesAnyPort() {
        channel = new EmbeddedChannel(new DnsRebindingProtectionHandler(List.of("[2001:db8::1]")));
        assertThat(channel.writeInbound(requestWithHost("[2001:db8::1]:8096")))
                .as("a host-only IPv6 entry must match any port")
                .isTrue();
    }

    @Test
    void releasesRejectedInboundRequest() {
        channel = new EmbeddedChannel(new DnsRebindingProtectionHandler());
        var req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/mcp");
        req.headers().set(HttpHeaderNames.HOST, "host.docker.internal:8096");
        channel.writeInbound(req);
        assertThat(req.refCnt())
                .as("a rejected inbound request must be released, not leaked")
                .isZero();
        assertThat(rejectionStatus(channel)).isEqualTo(HttpResponseStatus.FORBIDDEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost:garbage", "[::1]evil", "[::1]:80x"})
    void rejectsMalformedPortSuffix(String host) {
        channel = new EmbeddedChannel(new DnsRebindingProtectionHandler());
        channel.writeInbound(requestWithHost(host));
        assertThat(rejectionStatus(channel)).isEqualTo(HttpResponseStatus.FORBIDDEN);
    }

    @Test
    void rejectsMultipleOriginHeaders() {
        channel = new EmbeddedChannel(new DnsRebindingProtectionHandler());
        var req = requestWithHost("localhost:8096");
        req.headers().add(HttpHeaderNames.ORIGIN, "http://localhost:3000");
        req.headers().add(HttpHeaderNames.ORIGIN, "http://evil.example");
        channel.writeInbound(req);
        assertThat(rejectionStatus(channel)).isEqualTo(HttpResponseStatus.FORBIDDEN);
    }

    @Test
    void dropsContentChunksAfterRejection() {
        channel = new EmbeddedChannel(new DnsRebindingProtectionHandler());
        channel.writeInbound(request(HttpVersion.HTTP_1_1, "evil.example"));
        assertThat(rejectionStatus(channel)).isEqualTo(HttpResponseStatus.FORBIDDEN);
        var chunk = new io.netty.handler.codec.http.DefaultHttpContent(
                io.netty.buffer.Unpooled.wrappedBuffer(new byte[] {1}));
        channel.writeInbound(chunk);
        assertThat(channel.inboundMessages())
                .as("orphan content must not propagate")
                .isEmpty();
        assertThat(chunk.refCnt()).as("dropped chunk must be released").isZero();
    }

    @Test
    void rejectsMultipleHostHeaders() {
        channel = new EmbeddedChannel(new DnsRebindingProtectionHandler());
        var req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/mcp");
        req.headers().add(HttpHeaderNames.HOST, "localhost:8096");
        req.headers().add(HttpHeaderNames.HOST, "evil.example");
        channel.writeInbound(req);
        assertThat(rejectionStatus(channel)).isEqualTo(HttpResponseStatus.FORBIDDEN);
    }

    @Test
    void rejectsMissingHostOnHttp11() {
        channel = new EmbeddedChannel(new DnsRebindingProtectionHandler());
        channel.writeInbound(request(HttpVersion.HTTP_1_1, null));
        assertThat(rejectionStatus(channel)).isEqualTo(HttpResponseStatus.FORBIDDEN);
    }

    @Test
    void rejectsEmptyHostOnHttp11() {
        channel = new EmbeddedChannel(new DnsRebindingProtectionHandler());
        channel.writeInbound(requestWithHost(""));
        assertThat(rejectionStatus(channel)).isEqualTo(HttpResponseStatus.FORBIDDEN);
    }

    @Test
    void allowsMissingHostOnHttp10() {
        channel = new EmbeddedChannel(new DnsRebindingProtectionHandler());
        assertThat(channel.writeInbound(request(HttpVersion.HTTP_1_0, null)))
                .as("HTTP/1.0 permits omitting the Host header")
                .isTrue();
    }

    @Test
    void portSpecificEntryDoesNotMatchAnotherPort() {
        channel = new EmbeddedChannel(new DnsRebindingProtectionHandler(List.of("host.docker.internal:8096")));
        channel.writeInbound(requestWithHost("host.docker.internal:9000"));
        assertThat(rejectionStatus(channel)).isEqualTo(HttpResponseStatus.FORBIDDEN);
    }

    @Test
    void nonLocalBrowserOriginStillRejectedEvenWhenHostIsAllowlisted() {
        channel = new EmbeddedChannel(new DnsRebindingProtectionHandler(List.of("host.docker.internal:8096")));
        var req = requestWithHost("host.docker.internal:8096");
        req.headers().set(HttpHeaderNames.ORIGIN, "http://host.docker.internal:3000");
        channel.writeInbound(req);
        assertThat(rejectionStatus(channel))
                .as("allowedHosts must not widen the localhost-only Origin check")
                .isEqualTo(HttpResponseStatus.FORBIDDEN);
    }

    @Test
    void allowlistEntryIsTrimmed() {
        channel = new EmbeddedChannel(new DnsRebindingProtectionHandler(List.of("  host.docker.internal:8096  ")));
        assertThat(channel.writeInbound(requestWithHost("host.docker.internal:8096")))
                .isTrue();
    }

    @Test
    void constructorRejectsUrlEntry() {
        assertThatThrownBy(() -> new DnsRebindingProtectionHandler(List.of("http://host.docker.internal:8096")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructorRejectsEntryWithInternalWhitespace() {
        assertThatThrownBy(() -> new DnsRebindingProtectionHandler(List.of("host docker:8096")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
