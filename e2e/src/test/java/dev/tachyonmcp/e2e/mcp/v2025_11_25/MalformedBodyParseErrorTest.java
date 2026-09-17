/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2025_11_25;

import static dev.tachyonmcp.testkit.McpHttpResponseAssert.assertThatResponse;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The session-bearing dispatch sink, {@code McpOperationHandler#parseAndDispatchPost}, reached with
 * a body whose parse already failed upstream and was cached ({@code transport.netty.PeekedBody}).
 * Both branches of that sink are exercised here, because only one of them is new:
 *
 * <ul>
 *   <li>with an {@code Mcp-Method} mirror, the version-independent
 *       {@code McpMirrorValidationHandler} peeks first, so the handler consumes a cache entry that
 *       holds no message and never parses;
 *   <li>without a mirror nothing peeks, so the handler parses the body itself — the fallback the
 *       cache must leave intact.
 * </ul>
 *
 * Both must answer JSON-RPC {@code -32700} with HTTP {@code 400}. 2025-11-25 otherwise maps every
 * JSON-RPC error to HTTP 200, but a parse failure predates protocol mapping: there is no negotiated
 * envelope to answer in, so the transport fixes the status itself.
 */
class MalformedBodyParseErrorTest extends AbstractStatefulMcpE2eTest {

    @Test
    void peekedMalformedBodyStillYieldsParseError() throws Exception {
        try (var client = createTestClient()) {
            var sessionId = client.initialize();

            var response = client.post(sessionId, "{ not json", Map.of("Mcp-Method", "tools/call"));

            assertThatResponse(response)
                    .hasStatus(400)
                    .isJsonRpcError()
                    .hasErrorCode(-32700)
                    .hasErrorMessage("Parse error");
        }
    }

    @Test
    void unpeekedMalformedBodyStillYieldsParseError() throws Exception {
        try (var client = createTestClient()) {
            var sessionId = client.initialize();

            var response = client.post(sessionId, "{ not json");

            assertThatResponse(response)
                    .hasStatus(400)
                    .isJsonRpcError()
                    .hasErrorCode(-32700)
                    .hasErrorMessage("Parse error");
        }
    }

    /**
     * Valid JSON in the wrong shape earns {@code -32600} on this sink too. The classification used
     * to reach only the session-less initialization sink, so the same body answered {@code -32600}
     * there and {@code -32700} here — and, on this version, differed again depending on whether a
     * mirror header happened to arm the peek. Both branches are pinned for that reason.
     */
    @Test
    void peekedNonJsonRpcObjectYieldsInvalidRequest() throws Exception {
        try (var client = createTestClient()) {
            var sessionId = client.initialize();

            var response = client.post(sessionId, "{\"hello\":\"world\"}", Map.of("Mcp-Method", "tools/call"));

            assertThatResponse(response)
                    .hasStatus(400)
                    .isJsonRpcError()
                    .hasErrorCode(-32600)
                    .hasErrorMessage("Invalid Request");
        }
    }

    @Test
    void unpeekedNonJsonRpcObjectYieldsInvalidRequest() throws Exception {
        try (var client = createTestClient()) {
            var sessionId = client.initialize();

            var response = client.post(sessionId, "{\"hello\":\"world\"}");

            assertThatResponse(response)
                    .hasStatus(400)
                    .isJsonRpcError()
                    .hasErrorCode(-32600)
                    .hasErrorMessage("Invalid Request");
        }
    }

    /** An empty body never was JSON, so it is a parse failure and not an envelope violation. */
    @Test
    void emptyBodyYieldsParseError() throws Exception {
        try (var client = createTestClient()) {
            var sessionId = client.initialize();

            var response = client.post(sessionId, "");

            assertThatResponse(response)
                    .hasStatus(400)
                    .isJsonRpcError()
                    .hasErrorCode(-32700)
                    .hasErrorMessage("Parse error");
        }
    }
}
