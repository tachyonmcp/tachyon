/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2026_07_28;

import static dev.tachyonmcp.testkit.McpHttpResponseAssert.assertThatResponse;

import dev.tachyonmcp.e2e.mcp.AbstractStatelessMcpE2eTest;
import dev.tachyonmcp.testkit.Mcp20260728Client;
import dev.tachyonmcp.testkit.McpTestClients;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A malformed POST body is parsed by a validation handler before it ever reaches dispatch, and the
 * failed parse is cached on the channel rather than re-attempted
 * ({@code transport.netty.PeekedBody}). The dispatch site then reads a cache entry that exists but
 * holds no message — a state the pre-cache code could not produce, because it re-parsed and let the
 * parse throw. This pins that the peeked failure still surfaces as a JSON-RPC error with HTTP
 * {@code 400} and not as a crash or an empty response — {@code -32700} for a JSON syntax failure,
 * {@code -32600} for well-formed JSON that isn't a JSON-RPC envelope.
 *
 * <p>2026-07-28 has no protocol sessions (SEP-2575), so every POST is answered by
 * {@code McpInitializationHandler#handlePostWithoutSession}. Its {@code RequestValidationHandler}
 * peeks unconditionally, so no mirror header is needed to arm the cache. The session-bearing sink,
 * {@code McpOperationHandler}, is covered by the 2025-11-25 test of the same name.
 */
class MalformedBodyParseErrorTest extends AbstractStatelessMcpE2eTest<Mcp20260728Client> {

    @Override
    protected Mcp20260728Client createTestClient() {
        return createTestClient(port);
    }

    @Override
    protected Mcp20260728Client createTestClient(int port) {
        return McpTestClients.latest(port);
    }

    @Test
    void peekedMalformedBodyStillYieldsParseError() throws Exception {
        var response = postMcpRequest("{ not json", Map.of());

        assertThatResponse(response)
                .hasStatus(400)
                .isJsonRpcError()
                .hasErrorCode(-32700)
                .hasErrorMessage("Parse error");
    }

    /**
     * Well-formed JSON that is not a JSON-RPC envelope is cached distinctly from a syntax failure
     * ({@code transport.netty.PeekedBody.Parsed#invalidRequest}), so the dispatch site answers
     * {@code -32600} (invalid request) rather than {@code -32700} (parse error).
     */
    @Test
    void peekedNonJsonRpcObjectYieldsInvalidRequest() throws Exception {
        var response = postMcpRequest("{\"hello\":\"world\"}", Map.of());

        assertThatResponse(response)
                .hasStatus(400)
                .isJsonRpcError()
                .hasErrorCode(-32600)
                .hasErrorMessage("Invalid Request");
    }

    /**
     * A body that never produced a JSON value is a parse failure, not an envelope violation. The
     * root-token check used to reject a non-object root before reading the rest of the body, so
     * these answered {@code -32600}.
     */
    @Test
    void emptyBodyYieldsParseError() throws Exception {
        var response = postMcpRequest("", Map.of());

        assertThatResponse(response)
                .hasStatus(400)
                .isJsonRpcError()
                .hasErrorCode(-32700)
                .hasErrorMessage("Parse error");
    }

    @Test
    void truncatedArrayBodyYieldsParseError() throws Exception {
        var response = postMcpRequest("[{\"jsonrpc\":\"2.0\"", Map.of());

        assertThatResponse(response)
                .hasStatus(400)
                .isJsonRpcError()
                .hasErrorCode(-32700)
                .hasErrorMessage("Parse error");
    }
}
