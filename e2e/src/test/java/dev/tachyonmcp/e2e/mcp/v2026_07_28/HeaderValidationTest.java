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
 * {@code Mcp-Name} HTTP headers (SEP-2243). This is the revision that made the mirrors mandatory
 * ({@code v2026_07_28.transport.RequiredHeadersHandler}); that a mirror which is present agrees
 * with the body is checked first, on every revision, by
 * {@code transport.netty.http.McpHeaderMatchHandler}. Either failure is JSON-RPC {@code -32020}
 * (HeaderMismatch) under HTTP {@code 400 Bad Request}. The older revision's optional-mirror cases
 * live in {@code v2025_11_25.HeaderValidationTest}.
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

    private void assertHeaderMismatch(HttpResponse<String> response, String offendingHeader, String reason) {
        assertThatResponse(response)
                .hasStatus(400)
                .isJsonRpcError()
                .hasId(9)
                .hasErrorCode(-32020)
                .hasErrorMessageContaining(offendingHeader)
                .hasErrorMessageContaining(reason);
    }

    @Test
    void rejectsMismatchedNameHeader() throws Exception {
        assertHeaderMismatch(post("tools/call", "not_echo"), "Mcp-Name", "does not match");
    }

    @Test
    void rejectsMissingNameHeaderWhenBodyHasName() throws Exception {
        assertHeaderMismatch(post("tools/call", null), "Mcp-Name", "is required");
    }

    @Test
    void rejectsMismatchedMethodHeader() throws Exception {
        assertHeaderMismatch(post("tools/list", "echo"), "Mcp-Method", "does not match");
    }

    @Test
    void rejectsMissingMethodHeader() throws Exception {
        assertHeaderMismatch(post(null, "echo"), "Mcp-Method", "is required");
    }

    /**
     * Agreement is checked before presence: a mirror that lies about the body is the graver fault,
     * and the same rule every revision applies, so it is reported ahead of what this revision alone
     * demands.
     */
    @Test
    void reportsMismatchBeforeMissing() throws Exception {
        assertHeaderMismatch(post("tools/list", null), "Mcp-Method", "does not match");
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
}
