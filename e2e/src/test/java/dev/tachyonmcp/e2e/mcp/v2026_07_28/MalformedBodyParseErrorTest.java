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
 * parse throw. This pins that the peeked failure still surfaces as JSON-RPC {@code -32700} with
 * HTTP {@code 400} and not as a crash or an empty response.
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
     * Well-formed JSON that is not a JSON-RPC envelope fails the same way: the peek caches the
     * rejection rather than the shape, so the dispatch site cannot tell the two apart — and must not
     * need to.
     */
    @Test
    void peekedNonJsonRpcObjectStillYieldsParseError() throws Exception {
        var response = postMcpRequest("{\"hello\":\"world\"}", Map.of());

        assertThatResponse(response)
                .hasStatus(400)
                .isJsonRpcError()
                .hasErrorCode(-32700)
                .hasErrorMessage("Parse error");
    }
}
