/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2026_07_28;

import static dev.tachyonmcp.testkit.McpHttpResponseAssert.assertThatResponse;

import dev.tachyonmcp.e2e.mcp.AbstractStatelessMcpE2eTest;
import dev.tachyonmcp.testkit.Mcp20260728Client;
import dev.tachyonmcp.testkit.McpClient;
import dev.tachyonmcp.testkit.McpTestClients;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * MCP 2026-07-28 mirrors {@code method}/{@code params.name} into the {@code Mcp-Method}/
 * {@code Mcp-Name} HTTP headers (SEP-2243), required and validated by this revision's own
 * {@code RequestValidationHandler}. A server processing the body must reject a request where the
 * header doesn't match with JSON-RPC {@code -32020} (HeaderMismatch) and HTTP {@code 400 Bad
 * Request}. On an older or not-yet-negotiated request (e.g. the {@code initialize} handshake) the
 * mirror is optional rather than required, but {@code transport.netty.http.McpMirrorValidationHandler}
 * still checks agreement on every version: a mirror that disagrees with the body
 * is rejected as 200/{@code -32001} under 2025-11-25's mapping, while one that agrees,
 * or is simply absent, is accepted.
 */
class HeaderValidationTest extends AbstractStatelessMcpE2eTest<McpClient> {

    @Override
    protected Mcp20260728Client createTestClient() {
        return createTestClient(port);
    }

    @Override
    protected Mcp20260728Client createTestClient(int port) {
        return McpTestClients.latest(port);
    }

    // language=JSON
    private static final String TOOLS_CALL_ECHO_BODY = """
            {
              "jsonrpc": "2.0",
              "id": 9,
              "method": "tools/call",
              "params": {
                "name": "echo",
                "arguments": {"message": "hi"},
                "_meta": {
                  "io.modelcontextprotocol/protocolVersion": "2026-07-28",
                  "io.modelcontextprotocol/clientInfo": {"name": "t", "version": "1"},
                  "io.modelcontextprotocol/clientCapabilities": {}
                }
              }
            }
            """;

    private HttpResponse<String> post(String mcpMethodHeader, String mcpNameHeader) throws Exception {
        var headers = new LinkedHashMap<String, String>();
        if (mcpMethodHeader != null) headers.put("Mcp-Method", mcpMethodHeader);
        if (mcpNameHeader != null) headers.put("Mcp-Name", mcpNameHeader);
        return postMcpRequest(TOOLS_CALL_ECHO_BODY, headers);
    }

    private void assertHeaderMismatch(HttpResponse<String> response) {
        assertThatResponse(response).hasStatus(400).isJsonRpcError().hasId(9).hasErrorCode(-32020);
    }

    @Test
    void rejectsMismatchedNameHeader() throws Exception {
        assertHeaderMismatch(post("tools/call", "not_echo"));
    }

    @Test
    void rejectsMissingNameHeaderWhenBodyHasName() throws Exception {
        assertHeaderMismatch(post("tools/call", null));
    }

    @Test
    void rejectsMismatchedMethodHeader() throws Exception {
        assertHeaderMismatch(post("tools/list", "echo"));
    }

    @Test
    void acceptsWhitespacePaddedNameHeader() throws Exception {
        var response = post("tools/call", "  echo  ");
        assertThatResponse(response).hasStatus(200).isSuccess().hasTextContent("hi");
    }

    @Test
    void acceptsBase64EncodedNameHeader() throws Exception {
        var encoded = Base64.getEncoder().encodeToString("echo".getBytes(StandardCharsets.UTF_8));
        var response = post("tools/call", "=?base64?" + encoded + "?=");
        assertThatResponse(response).hasStatus(200).isSuccess().hasTextContent("hi");
    }

    @Test
    void rejectsProtocolVersionHeaderMismatchingMeta() throws Exception {
        // language=JSON
        var body = """
                {
                  "jsonrpc": "2.0",
                  "id": 11,
                  "method": "tools/call",
                  "params": {
                    "name": "echo",
                    "arguments": {"message": "hi"},
                    "_meta": {
                      "io.modelcontextprotocol/protocolVersion": "2099-01-01",
                      "io.modelcontextprotocol/clientInfo": {"name": "t", "version": "1"},
                      "io.modelcontextprotocol/clientCapabilities": {}
                    }
                  }
                }
                """;
        var response = postMcpRequest(body, Map.of("Mcp-Method", "tools/call", "Mcp-Name", "echo"));

        assertThatResponse(response).hasStatus(400).isJsonRpcError().hasId(11).hasErrorCode(-32020);
    }

    /** Rejected pre-aggregation, so the response is a plain {@code 400}, not JSON-RPC {@code -32020}. */
    private void assertDuplicateHeaderRejected(HttpResponse<String> response) {
        assertThatResponse(response).isRejectedWith(400, "Duplicate MCP header");
    }

    @Test
    void rejectsDuplicateMethodHeaders() throws Exception {
        assertDuplicateHeaderRejected(postMcpRequest(
                TOOLS_CALL_ECHO_BODY,
                Map.of("Mcp-Method", List.of("tools/call", "tools/list"), "Mcp-Name", List.of("echo")),
                true));
    }

    @Test
    void rejectsDuplicateNameHeaders() throws Exception {
        assertDuplicateHeaderRejected(postMcpRequest(
                TOOLS_CALL_ECHO_BODY,
                Map.of("Mcp-Method", List.of("tools/call"), "Mcp-Name", List.of("echo", "other_tool")),
                true));
    }

    @Test
    void rejectsDuplicateIdenticalNameHeaders() throws Exception {
        assertDuplicateHeaderRejected(postMcpRequest(
                TOOLS_CALL_ECHO_BODY,
                Map.of("Mcp-Method", List.of("tools/call"), "Mcp-Name", List.of("echo", "echo")),
                true));
    }

    /** Duplicates here are a downgrade: first value negotiates, an intermediary reads the last. */
    @Test
    void rejectsDuplicateProtocolVersionHeaders() throws Exception {
        assertDuplicateHeaderRejected(postMcpRequest(
                TOOLS_CALL_ECHO_BODY,
                Map.of(
                        "MCP-Protocol-Version", List.of("2025-11-25", "2026-07-28"),
                        "Mcp-Method", List.of("tools/call"),
                        "Mcp-Name", List.of("echo")),
                false));
    }

    /**
     * 2025-11-25 ties every JSON-RPC error to HTTP 200 (the classic JSON-RPC-over-HTTP convention —
     * see {@code JsonRpcError#httpStatus}) and, unlike 2026-07-28's reassigned {@code -32020}, still
     * uses this error's original SEP-2243 code {@code -32001}, so a mismatch on an older revision
     * reads as 200/-32001 rather than 400/-32020.
     */
    private void assertHeaderMismatchOnOlderRevision(HttpResponse<String> response) {
        assertThatResponse(response).hasStatus(200).isJsonRpcError().hasId(9).hasErrorCode(-32001);
    }

    /**
     * A mirror is never required before 2026-07-28 — but a client that sends one anyway on an older
     * revision still has it checked against the body, by the version-independent {@code
     * McpMirrorValidationHandler}: a gateway must not authorize a benign {@code Mcp-Method} while the
     * body ran something else, regardless of which revision negotiated.
     */
    @Test
    void rejectsMismatchedMirroredHeaderOnOlderProtocolVersion() throws Exception {
        var response = postMcpRequest(
                TOOLS_CALL_ECHO_BODY,
                Map.of(
                        "MCP-Protocol-Version", List.of("2025-11-25"),
                        "Mcp-Method", List.of("tools/list"),
                        "Mcp-Name", List.of("echo")),
                false);

        assertHeaderMismatchOnOlderRevision(response);
    }

    @Test
    void rejectsMismatchedMirroredHeaderWithNoProtocolVersion() throws Exception {
        var response = postMcpRequest(
                TOOLS_CALL_ECHO_BODY, Map.of("Mcp-Method", List.of("tools/list"), "Mcp-Name", List.of("echo")), false);

        assertHeaderMismatchOnOlderRevision(response);
    }

    /**
     * SEP-2243's own canonical example mirrors {@code Mcp-Method: initialize} on the handshake
     * request itself, which by construction cannot yet carry a negotiated {@code
     * MCP-Protocol-Version}. A mirror that agrees with the body must be accepted on an
     * older/not-yet-negotiated request, not treated as a downgrade attempt.
     */
    @Test
    void acceptsMatchingMirroredHeadersOnOlderProtocolVersion() throws Exception {
        var response = postMcpRequest(
                TOOLS_CALL_ECHO_BODY,
                Map.of(
                        "MCP-Protocol-Version", List.of("2025-11-25"),
                        "Mcp-Method", List.of("tools/call"),
                        "Mcp-Name", List.of("echo")),
                false);

        assertThatResponse(response).hasStatus(200).isSuccess().hasTextContent("hi");
    }

    @Test
    void acceptsMatchingMirroredHeadersWithNoProtocolVersion() throws Exception {
        var response = postMcpRequest(
                TOOLS_CALL_ECHO_BODY, Map.of("Mcp-Method", List.of("tools/call"), "Mcp-Name", List.of("echo")), false);

        assertThatResponse(response).hasStatus(200).isSuccess().hasTextContent("hi");
    }

    /**
     * The handshake itself, verbatim from SEP-2243's "Other Request Methods" example: {@code
     * Mcp-Method: initialize} and no {@code MCP-Protocol-Version}, because there is nothing
     * negotiated yet to put there. Rejecting this was what broke every {@code mcp-remote} connection
     * before the real session started — its preflight self-test sends exactly this — so the
     * regression is worth pinning at the one method whose request is answered by a different
     * handler than every other.
     */
    @Test
    void acceptsMethodMirrorOnTheInitializeHandshake() throws Exception {
        // language=JSON
        var body = """
                {
                  "jsonrpc": "2.0",
                  "id": 4,
                  "method": "initialize",
                  "params": {
                    "protocolVersion": "2025-11-25",
                    "capabilities": {},
                    "clientInfo": {"name": "ExampleClient", "version": "1.0.0"}
                  }
                }
                """;

        var response = postMcpRequest(body, Map.of("Mcp-Method", List.of("initialize")), false);

        assertThatResponse(response).hasStatus(200).isSuccess().hasId(4);
    }

    /** The same handshake with a mirror naming another method is still a mismatch. */
    @Test
    void rejectsMismatchedMethodMirrorOnTheInitializeHandshake() throws Exception {
        // language=JSON
        var body = """
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

        var response = postMcpRequest(body, Map.of("Mcp-Method", List.of("tools/call")), false);

        assertHeaderMismatchOnOlderRevision(response);
    }
}
