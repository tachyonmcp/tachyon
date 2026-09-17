/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2025_11_25;

import static dev.tachyonmcp.testkit.McpHttpResponseAssert.assertThatResponse;

import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.e2e.mcp.AbstractStatelessMcpE2eTest;
import dev.tachyonmcp.e2e.mcp.EchoToolHandler;
import dev.tachyonmcp.testkit.Mcp20251125Client;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SEP-2243 header mirrors ({@code Mcp-Method}, {@code Mcp-Name}, {@code Mcp-Param-*}) on a
 * 2025-11-25 request — or on one that names no {@code MCP-Protocol-Version} at all, which
 * negotiates 2025-11-25 too. This revision predates the mirrors, so none is required; but a mirror
 * a client sends anyway must agree with the body, on every revision: a gateway routing on the
 * header must never authorize something other than what the server executes. That check is the
 * version-independent {@code transport.netty.http.McpHeaderMatchHandler}.
 *
 * <p>2025-11-25 ties every JSON-RPC error to HTTP 200 and keeps the error's original SEP-2243 code
 * {@code -32001}, so a mismatch reads as 200/-32001 rather than 2026-07-28's 400/-32020.
 */
class HeaderValidationTest extends AbstractStatelessMcpE2eTest<Mcp20251125Client> {

    private static final String VERSION = "2025-11-25";

    // language=JSON
    private static final String EXECUTE_SQL_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "region": {"type": "string", "x-mcp-header": "Region"},
                "query": {"type": "string"}
              },
              "required": ["region", "query"]
            }
            """;

    // language=JSON
    private static final String TOOLS_CALL_ECHO_BODY = """
            {
              "jsonrpc": "2.0",
              "id": 9,
              "method": "tools/call",
              "params": {"name": "echo", "arguments": {"message": "hi"}}
            }
            """;

    // language=JSON
    private static final String TOOLS_CALL_EXECUTE_SQL_BODY = """
            {
              "jsonrpc": "2.0",
              "id": 9,
              "method": "tools/call",
              "params": {"name": "execute_sql", "arguments": {"region": "us-west1", "query": "SELECT 1"}}
            }
            """;

    // language=JSON
    private static final String INITIALIZE_BODY = """
            {
              "jsonrpc": "2.0",
              "id": 9,
              "method": "initialize",
              "params": {
                "protocolVersion": "2025-11-25",
                "capabilities": {},
                "clientInfo": {"name": "ExampleClient", "version": "1.0.0"}
              }
            }
            """;

    @Override
    protected Mcp20251125Client createTestClient() {
        return createTestClient(port);
    }

    @Override
    protected Mcp20251125Client createTestClient(int port) {
        return new Mcp20251125Client(port);
    }

    @BeforeEach
    void registerFixtures() {
        startServer(b -> b.capabilities(c -> c.tools()), s -> {
            s.tools().registerAsync(EchoToolHandler.DESCRIPTOR, EchoToolHandler.FN);
            s.tools()
                    .register(
                            d -> d.name("execute_sql")
                                    .description("Executes SQL")
                                    .inputSchema(EXECUTE_SQL_SCHEMA),
                            (ctx, request) -> ToolResult.text(
                                    "region=" + request.arguments().stringOr("region", "") + " query="
                                            + request.arguments().stringOr("query", "")));
        });
    }

    /** Explicit 2025-11-25 header, plus whatever mirrors the test layers on top. */
    private HttpResponse<String> postVersioned(String body, Map<String, String> mirrors) throws Exception {
        var headers = new LinkedHashMap<String, List<String>>();
        headers.put("MCP-Protocol-Version", List.of(VERSION));
        mirrors.forEach((name, value) -> headers.put(name, List.of(value)));
        return postMcpRequest(body, headers, false);
    }

    /** No {@code MCP-Protocol-Version} at all — what a pre-negotiation client sends. */
    private HttpResponse<String> postUnversioned(String body, Map<String, String> mirrors) throws Exception {
        var headers = new LinkedHashMap<String, List<String>>();
        mirrors.forEach((name, value) -> headers.put(name, List.of(value)));
        return postMcpRequest(body, headers, false);
    }

    private static void assertHeaderMismatch(HttpResponse<String> response, String offendingHeader) {
        assertThatResponse(response)
                .hasStatus(200)
                .isJsonRpcError()
                .hasId(9)
                .hasErrorCode(-32001)
                .hasErrorMessageContaining(offendingHeader)
                .hasErrorMessageContaining("does not match");
    }

    private static void assertEchoed(HttpResponse<String> response) {
        assertThatResponse(response).hasStatus(200).isSuccess().hasId(9).hasTextContent("hi");
    }

    @Test
    void acceptsAbsentMirrors() throws Exception {
        assertEchoed(postVersioned(TOOLS_CALL_ECHO_BODY, Map.of()));
    }

    @Test
    void acceptsMatchingMirrors() throws Exception {
        assertEchoed(postVersioned(TOOLS_CALL_ECHO_BODY, Map.of("Mcp-Method", "tools/call", "Mcp-Name", "echo")));
    }

    @Test
    void acceptsMatchingMirrorsWithNoProtocolVersion() throws Exception {
        assertEchoed(postUnversioned(TOOLS_CALL_ECHO_BODY, Map.of("Mcp-Method", "tools/call", "Mcp-Name", "echo")));
    }

    @Test
    void rejectsMismatchedMethodMirror() throws Exception {
        var response = postVersioned(TOOLS_CALL_ECHO_BODY, Map.of("Mcp-Method", "tools/list", "Mcp-Name", "echo"));

        assertHeaderMismatch(response, "Mcp-Method");
    }

    @Test
    void rejectsMismatchedMethodMirrorWithNoProtocolVersion() throws Exception {
        var response = postUnversioned(TOOLS_CALL_ECHO_BODY, Map.of("Mcp-Method", "tools/list", "Mcp-Name", "echo"));

        assertHeaderMismatch(response, "Mcp-Method");
    }

    @Test
    void rejectsMismatchedNameMirror() throws Exception {
        var response = postVersioned(TOOLS_CALL_ECHO_BODY, Map.of("Mcp-Method", "tools/call", "Mcp-Name", "not_echo"));

        assertHeaderMismatch(response, "Mcp-Name");
    }

    /** {@code Mcp-Name} alone is a legitimate partial mirror: nothing here demands the full set. */
    @Test
    void acceptsMatchingNameMirrorWithoutMethodMirror() throws Exception {
        assertEchoed(postVersioned(TOOLS_CALL_ECHO_BODY, Map.of("Mcp-Name", "echo")));
    }

    /**
     * SEP-2243's own canonical example mirrors {@code Mcp-Method: initialize} on the handshake, which
     * by construction carries no negotiated {@code MCP-Protocol-Version}. Rejecting it broke every
     * {@code mcp-remote} connection — its preflight self-test sends exactly this.
     */
    @Test
    void acceptsMethodMirrorOnTheInitializeHandshake() throws Exception {
        var response = postUnversioned(INITIALIZE_BODY, Map.of("Mcp-Method", "initialize"));

        assertThatResponse(response).hasStatus(200).isSuccess().hasId(9);
    }

    @Test
    void rejectsMismatchedMethodMirrorOnTheInitializeHandshake() throws Exception {
        var response = postUnversioned(INITIALIZE_BODY, Map.of("Mcp-Method", "tools/call"));

        assertHeaderMismatch(response, "Mcp-Method");
    }

    /** {@code x-mcp-header} annotations demand nothing of this revision. */
    @Test
    void acceptsMissingParamMirrorWhenBodyHasValue() throws Exception {
        var response = postVersioned(TOOLS_CALL_EXECUTE_SQL_BODY, Map.of());

        assertThatResponse(response).hasStatus(200).isSuccess().hasTextContent("region=us-west1 query=SELECT 1");
    }

    @Test
    void acceptsAgreeingParamMirror() throws Exception {
        var response = postVersioned(TOOLS_CALL_EXECUTE_SQL_BODY, Map.of("Mcp-Param-Region", "us-west1"));

        assertThatResponse(response).hasStatus(200).isSuccess().hasTextContent("region=us-west1 query=SELECT 1");
    }

    /**
     * A gateway rate-limiting or routing on {@code Mcp-Param-Region: eu-west1} must not wave through
     * a body that queries {@code us-west1}.
     */
    @Test
    void rejectsMismatchedParamMirror() throws Exception {
        var response = postVersioned(
                TOOLS_CALL_EXECUTE_SQL_BODY,
                Map.of("Mcp-Method", "tools/call", "Mcp-Name", "execute_sql", "Mcp-Param-Region", "eu-west1"));

        assertHeaderMismatch(response, "Mcp-Param-Region");
    }
}
