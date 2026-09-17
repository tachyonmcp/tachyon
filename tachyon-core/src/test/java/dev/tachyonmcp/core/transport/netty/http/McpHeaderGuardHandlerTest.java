/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty.http;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.McpProtocol;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The HTTP view of a request and the executed view must not be able to diverge on a repeated
 * singleton MCP header — an intermediary reads the last value, Netty reads the first. Rejected with
 * {@code 400}, before anything downstream calls {@code headers().get}.
 *
 * <p>Whether an SEP-2243 mirror's value actually agrees with the body is a separate concern this
 * handler does not decide (it runs before the body is even aggregated): see {@link
 * McpHeaderMatchHandlerTest}.
 */
class McpHeaderGuardHandlerTest {

    private final EmbeddedChannel channel = new EmbeddedChannel(McpHeaderGuardHandler.INSTANCE);

    @AfterEach
    void releaseChannel() {
        channel.finishAndReleaseAll();
    }

    private static DefaultFullHttpRequest request() {
        var req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/mcp");
        req.headers().set(HttpHeaderNames.HOST, "localhost:8096");
        req.headers().set("MCP-Protocol-Version", McpProtocol.VERSION);
        return req;
    }

    /** Returns the status of a rejection response written outbound (releasing it), or {@code null}. */
    private @Nullable HttpResponseStatus rejectionStatus() {
        Object out = channel.readOutbound();
        try {
            return out instanceof HttpResponse resp ? resp.status() : null;
        } finally {
            ReferenceCountUtil.release(out);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"MCP-Protocol-Version", "MCP-Session-Id", "Mcp-Method", "Mcp-Name", "Last-Event-ID"})
    void rejectsDuplicateSingletonHeaderWithDifferentValues(String header) {
        var req = request();
        req.headers().add(header, "first");
        req.headers().add(header, "second");
        channel.writeInbound(req);
        assertThat(rejectionStatus()).isEqualTo(HttpResponseStatus.BAD_REQUEST);
        assertThat(channel.inboundMessages())
                .as("a rejected request must not reach the next handler")
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"MCP-Protocol-Version", "MCP-Session-Id", "Mcp-Method", "Mcp-Name", "Last-Event-ID"})
    void rejectsDuplicateSingletonHeaderWithIdenticalValues(String header) {
        var req = request();
        req.headers().add(header, "same");
        req.headers().add(header, "same");
        channel.writeInbound(req);
        assertThat(rejectionStatus())
                .as("identical duplicates are still ambiguous about whether the field is singular")
                .isEqualTo(HttpResponseStatus.BAD_REQUEST);
    }

    @Test
    void rejectsDuplicateHeaderNamesDifferingOnlyInCase() {
        var req = request();
        req.headers().add("Mcp-Method", "tools/call");
        req.headers().add("mcp-method", "tools/list");
        channel.writeInbound(req);
        assertThat(rejectionStatus())
                .as("HTTP field names are case-insensitive")
                .isEqualTo(HttpResponseStatus.BAD_REQUEST);
    }

    @Test
    void rejectsDuplicateParamHeaders() {
        var req = request();
        req.headers().add("Mcp-Param-Region", "us-west1");
        req.headers().add("Mcp-Param-Region", "eu-west1");
        channel.writeInbound(req);
        assertThat(rejectionStatus()).isEqualTo(HttpResponseStatus.BAD_REQUEST);
    }

    @Test
    void rejectsDuplicateParamHeadersDifferingOnlyInCase() {
        var req = request();
        req.headers().add("Mcp-Param-Region", "us-west1");
        req.headers().add("mcp-param-region", "eu-west1");
        channel.writeInbound(req);
        assertThat(rejectionStatus()).isEqualTo(HttpResponseStatus.BAD_REQUEST);
    }

    @Test
    void forwardsDistinctParamHeaders() {
        var req = request();
        req.headers().add("Mcp-Param-Region", "us-west1");
        req.headers().add("Mcp-Param-Zone", "a");
        assertThat(channel.writeInbound(req))
                .as("two different mirrored arguments are not a duplicate")
                .isTrue();
        assertThat(rejectionStatus()).isNull();
    }

    @Test
    void rejectsDuplicateSessionIdOnDelete() {
        var req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.DELETE, "/mcp");
        req.headers().add("MCP-Session-Id", "sess_a");
        req.headers().add("MCP-Session-Id", "sess_b");
        channel.writeInbound(req);
        assertThat(rejectionStatus()).as("session binding is not POST-only").isEqualTo(HttpResponseStatus.BAD_REQUEST);
    }

    @Test
    void forwardsSingleValuedMcpHeaders() {
        var req = request();
        req.headers().add("Mcp-Method", "tools/call");
        req.headers().add("Mcp-Name", "echo");
        req.headers().add("Mcp-Param-Region", "us-west1");
        assertThat(channel.writeInbound(req)).isTrue();
        assertThat(rejectionStatus()).isNull();
    }

    @Test
    void forwardsDuplicateNonMcpHeaders() {
        var req = request();
        req.headers().add(HttpHeaderNames.ACCEPT, "application/json");
        req.headers().add(HttpHeaderNames.ACCEPT, "text/event-stream");
        assertThat(channel.writeInbound(req))
                .as("Accept is legitimately repeatable; only MCP singletons are policed")
                .isTrue();
        assertThat(rejectionStatus()).isNull();
    }

    @Test
    void releasesRejectedInboundRequest() {
        var req = request();
        req.headers().add("Mcp-Method", "tools/call");
        req.headers().add("Mcp-Method", "tools/list");
        channel.writeInbound(req);
        assertThat(req.refCnt())
                .as("a rejected inbound request must be released, not leaked")
                .isZero();
        assertThat(rejectionStatus()).isEqualTo(HttpResponseStatus.BAD_REQUEST);
    }

    /**
     * A mirror is a 2026-07-28 introduction, so an older or not-yet-negotiated request is never
     * required to carry one — and this handler runs before {@code http-aggregator}, so it cannot
     * compare the mirror to the body anyway. Whether the value agrees with the body is checked
     * downstream, once the body is available: see {@link
     * McpHeaderMatchHandlerTest#rejectsMismatchedMethodMirrorWhereItIsOptional}.
     */
    @ParameterizedTest
    @ValueSource(strings = {"Mcp-Method", "Mcp-Name", "Mcp-Param-Region"})
    void forwardsMirroredHeaderOnOlderProtocolVersion(String header) {
        var req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/mcp");
        req.headers().set(HttpHeaderNames.HOST, "localhost:8096");
        req.headers().set("MCP-Protocol-Version", "2025-11-25");
        req.headers().add(header, "tools/list");
        assertThat(channel.writeInbound(req)).isTrue();
        assertThat(rejectionStatus()).isNull();
    }

    /**
     * The {@code initialize} handshake cannot yet know the negotiated version, so it never carries
     * {@code MCP-Protocol-Version} — a compliant SEP-2243 client may still mirror {@code Mcp-Method}
     * on it (this is the SEP's own canonical example). This handler must let it through; only the
     * body/header agreement is checked downstream.
     */
    @ParameterizedTest
    @ValueSource(strings = {"Mcp-Method", "Mcp-Name", "Mcp-Param-Region"})
    void forwardsMirroredHeaderWithNoProtocolVersion(String header) {
        var req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/mcp");
        req.headers().set(HttpHeaderNames.HOST, "localhost:8096");
        req.headers().add(header, "tools/list");
        assertThat(channel.writeInbound(req)).isTrue();
        assertThat(rejectionStatus()).isNull();
    }

    @Test
    void forwardsOlderProtocolVersionWithoutMirroredHeaders() {
        var req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/mcp");
        req.headers().set(HttpHeaderNames.HOST, "localhost:8096");
        req.headers().set("MCP-Protocol-Version", "2025-11-25");
        assertThat(channel.writeInbound(req))
                .as("older clients never sent the mirrors; nothing to guard")
                .isTrue();
        assertThat(rejectionStatus()).isNull();
    }

    @Test
    void rejectsDuplicateVersionHeaderRegardlessOfMirrors() {
        var req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/mcp");
        req.headers().set(HttpHeaderNames.HOST, "localhost:8096");
        req.headers().add("MCP-Protocol-Version", "2025-11-25");
        req.headers().add("MCP-Protocol-Version", McpProtocol.VERSION);
        req.headers().add("Mcp-Method", "tools/call");
        channel.writeInbound(req);
        assertThat(rejectionStatus())
                .as("MCP-Protocol-Version is a singleton header regardless of any mirror present")
                .isEqualTo(HttpResponseStatus.BAD_REQUEST);
    }
}
