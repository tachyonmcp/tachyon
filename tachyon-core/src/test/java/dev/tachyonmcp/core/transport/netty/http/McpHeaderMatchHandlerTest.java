/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.tachyonmcp.api.server.features.tools.Tools;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.server.json.JsonUtils;
import dev.tachyonmcp.core.transport.netty.ProtocolVersionHandler;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Whether a request must carry an SEP-2243 mirror is version-scoped; whether a mirror it does carry
 * agrees with the body is not. These exercise the agreement half — the whole of this handler — on
 * both revisions, including the
 * {@code 0x80}-{@code 0xFF} header bytes a real HTTP client refuses to transmit (it rejects them at
 * {@code header()} time or substitutes {@code '?'}), which is why this is a channel-level test and
 * not e2e — per the sibling {@code McpHeaderGuardHandlerTest}/{@code ProtocolVersionHandlerTest}.
 */
class McpHeaderMatchHandlerTest {

    private static final String V2026 = "2026-07-28";
    private static final String V2025 = "2025-11-25";

    /** JSON-RPC error code for a header/body mismatch under 2025-11-25's original SEP-2243 mapping. */
    private static final int HEADER_MISMATCH_2025 = -32001;

    /** 2026-07-28 reassigned the same failure to {@code -32020}. */
    private static final int HEADER_MISMATCH_2026 = -32020;

    private final EmbeddedChannel channel =
            new EmbeddedChannel(new ProtocolVersionHandler(), new McpHeaderMatchHandler(emptyServer()));

    private static ServerEngine emptyServer() {
        var server = mock(ServerEngine.class);
        var tools = mock(Tools.class);
        when(server.tools()).thenReturn(tools);
        when(tools.find(anyString())).thenReturn(Optional.empty());
        return server;
    }

    @AfterEach
    void tearDown() {
        channel.finishAndReleaseAll();
    }

    // language=JSON
    private static final String TOOLS_CALL_ECHO = """
            {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": "echo", "arguments": {}}}
            """;

    // language=JSON
    private static final String CANCELLED_NOTIFICATION = """
            {"jsonrpc": "2.0", "method": "notifications/cancelled", "params": {"requestId": "1"}}
            """;

    private static DefaultFullHttpRequest request(@Nullable String protocolVersion, String body) {
        var req = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/mcp", Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
        if (protocolVersion != null) {
            req.headers().set("MCP-Protocol-Version", protocolVersion);
        }
        return req;
    }

    /**
     * The JSON-RPC error code the handler rejected with, or {@code null} when it forwarded the
     * request instead. Asserting the code rather than the HTTP status keeps the two revisions
     * comparable: 2025-11-25 ties every JSON-RPC error to HTTP 200 (the classic
     * JSON-RPC-over-HTTP convention), 2026-07-28 surfaces this one as 400.
     */
    private @Nullable Integer rejectionCode() {
        Object out = channel.readOutbound();
        try {
            if (!(out instanceof FullHttpResponse resp)) return null;
            var error = JsonUtils.parse(resp.content().toString(StandardCharsets.UTF_8))
                    .path("error");
            return error.isObject() ? error.path("code").asInt() : null;
        } finally {
            ReferenceCountUtil.release(out);
        }
    }

    private void send(DefaultFullHttpRequest req) {
        channel.writeInbound(req);
    }

    // ---- agreement: enforced on every version, mirror optional or not -------------------------

    /**
     * {@code null} is the {@code initialize} preflight: SEP-2243's own canonical example mirrors
     * {@code Mcp-Method} on the handshake, which by construction cannot yet name a negotiated
     * version. It negotiates a pre-SEP-2243 revision, so the mirror is optional — but a mirror that
     * disagrees is still a gateway routing on an operation the body never ran.
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {V2025})
    void rejectsMismatchedMethodMirrorWhereItIsOptional(@Nullable String version) {
        var req = request(version, TOOLS_CALL_ECHO);
        req.headers().set("Mcp-Method", "tools/list");

        send(req);

        assertThat(rejectionCode()).isEqualTo(HEADER_MISMATCH_2025);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {V2025})
    void rejectsMismatchedNameMirrorWhereItIsOptional(@Nullable String version) {
        var req = request(version, TOOLS_CALL_ECHO);
        req.headers().set("Mcp-Method", "tools/call");
        req.headers().set("Mcp-Name", "not_echo");

        send(req);

        assertThat(rejectionCode()).isEqualTo(HEADER_MISMATCH_2025);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {V2025})
    void acceptsAgreeingMirrorsWhereTheyAreOptional(@Nullable String version) {
        var req = request(version, TOOLS_CALL_ECHO);
        req.headers().set("Mcp-Method", "tools/call");
        req.headers().set("Mcp-Name", "echo");

        send(req);

        assertThat(rejectionCode()).isNull();
        assertThat(channel.<Object>readInbound())
                .as("an agreeing request must reach dispatch")
                .isNotNull();
    }

    /** A URI whose target is {@code params.uri}, not {@code params.name} (SEP-2243). */
    private static final String RESOURCE_URI = "https://example.com/path/file%20name.txt?id=123";

    // language=JSON
    private static final String RESOURCES_READ = """
            {"jsonrpc": "2.0", "id": 2, "method": "resources/read", "params": {"uri": "%s"}}
            """.formatted(RESOURCE_URI);

    // language=JSON
    private static final String PROMPTS_GET = """
            {"jsonrpc": "2.0", "id": 3, "method": "prompts/get", "params": {"name": "code_review"}}
            """;

    /**
     * {@code resources/read} is the one addressed method whose target is not called {@code name}, so
     * a mirror compared against the wrong field would pass everything. The URI travels literally:
     * {@code /}, {@code ?} and {@code %} are plain-ASCII accepts in SEP-2243's conformance table, not
     * Base64 cases.
     */
    @ParameterizedTest
    @ValueSource(strings = {V2025, V2026})
    void acceptsNameMirrorAgreeingWithTheUriOfResourcesRead(String version) {
        var req = request(version, RESOURCES_READ);
        req.headers().set("Mcp-Method", "resources/read");
        req.headers().set("Mcp-Name", RESOURCE_URI);

        send(req);

        assertThat(rejectionCode()).isNull();
        assertThat(channel.<Object>readInbound()).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {V2025, V2026})
    void rejectsNameMirrorDisagreeingWithTheUriOfResourcesRead(String version) {
        var req = request(version, RESOURCES_READ);
        req.headers().set("Mcp-Method", "resources/read");
        req.headers().set("Mcp-Name", "https://example.com/other.txt");

        send(req);

        assertThat(rejectionCode()).isEqualTo(V2026.equals(version) ? HEADER_MISMATCH_2026 : HEADER_MISMATCH_2025);
    }

    /** The third addressed method — and the one whose {@code name} is a prompt's, never a tool's. */
    @ParameterizedTest
    @ValueSource(strings = {V2025, V2026})
    void rejectsNameMirrorDisagreeingWithTheNameOfPromptsGet(String version) {
        var req = request(version, PROMPTS_GET);
        req.headers().set("Mcp-Method", "prompts/get");
        req.headers().set("Mcp-Name", "other_prompt");

        send(req);

        assertThat(rejectionCode()).isEqualTo(V2026.equals(version) ? HEADER_MISMATCH_2026 : HEADER_MISMATCH_2025);
    }

    @ParameterizedTest
    @ValueSource(strings = {V2025, V2026})
    void acceptsNameMirrorAgreeingWithTheNameOfPromptsGet(String version) {
        var req = request(version, PROMPTS_GET);
        req.headers().set("Mcp-Method", "prompts/get");
        req.headers().set("Mcp-Name", "code_review");

        send(req);

        assertThat(rejectionCode()).isNull();
        assertThat(channel.<Object>readInbound()).isNotNull();
    }

    @Test
    void acceptsBase64WrappedNameMirror() {
        var encoded = Base64.getEncoder().encodeToString("echo".getBytes(StandardCharsets.UTF_8));
        var req = request(V2025, TOOLS_CALL_ECHO);
        req.headers().set("Mcp-Method", "tools/call");
        req.headers().set("Mcp-Name", "=?base64?" + encoded + "?=");

        send(req);

        assertThat(rejectionCode()).isNull();
    }

    /**
     * An absent mirror is never this handler's business, on any revision — there is nothing to
     * compare. Demanding one is {@code v2026_07_28.transport.RequiredHeadersHandler}'s job,
     * exercised by {@code CustomHeaderValidationTest} and {@code HeaderValidationTest} e2e.
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {V2025, V2026})
    void acceptsAbsentMirrors(@Nullable String version) {
        send(request(version, TOOLS_CALL_ECHO));

        assertThat(rejectionCode()).isNull();
        assertThat(channel.<Object>readInbound()).isNotNull();
    }

    /** Mcp-Method mirrors a notification's method too (2026-07-28 defines notifications/cancelled). */
    @Test
    void rejectsMismatchedMethodMirrorOnNotification() {
        var req = request(V2026, CANCELLED_NOTIFICATION);
        req.headers().set("Mcp-Method", "notifications/other");

        send(req);

        assertThat(rejectionCode()).isEqualTo(HEADER_MISMATCH_2026);
    }

    @Test
    void acceptsAgreeingMethodMirrorOnNotification() {
        var req = request(V2026, CANCELLED_NOTIFICATION);
        req.headers().set("Mcp-Method", "notifications/cancelled");

        send(req);

        assertThat(rejectionCode()).isNull();
        assertThat(channel.<Object>readInbound()).isNotNull();
    }

    // ---- wire format: a rule about the field, not about the message it travels with -----------

    /** Not scoped to tools/call, and not to a revision: SEP-2243 permits only HTAB and visible ASCII. */
    @ParameterizedTest
    @ValueSource(strings = {V2025, V2026})
    void rejectsParamMirrorCarryingNonAsciiCharacters(String version) {
        var req = request(version, CANCELLED_NOTIFICATION);
        req.headers().set("Mcp-Method", "notifications/cancelled");
        req.headers().set("Mcp-Param-Reason", "us-westé");

        send(req);

        assertThat(rejectionCode()).isEqualTo(V2026.equals(version) ? HEADER_MISMATCH_2026 : HEADER_MISMATCH_2025);
    }

    @Test
    void forwardsMalformedJsonForTheParseErrorPathDownstream() {
        var req = request(V2025, "{not json");
        req.headers().set("Mcp-Method", "tools/call");

        send(req);

        assertThat(rejectionCode())
                .as("a body this handler cannot read is not a mirror mismatch")
                .isNull();
        assertThat(channel.<Object>readInbound()).isNotNull();
    }
}
