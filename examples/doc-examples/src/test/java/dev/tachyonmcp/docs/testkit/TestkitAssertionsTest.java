package dev.tachyonmcp.docs.testkit;

import static dev.tachyonmcp.testkit.JsonRpcResponseAssert.assertThat;
import static dev.tachyonmcp.testkit.McpHttpResponseAssert.assertThatResponse;

import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.testkit.McpTestClients;
import dev.tachyonmcp.testkit.McpTestServers;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TestkitAssertionsTest {

    private static TachyonServer server;

    @BeforeAll
    static void startServer() {
        server = McpTestServers.start(
                b -> {},
                s -> {
                    s.tools().register(
                            tool -> tool.name("echo"),
                            (ctx, request) -> ToolResult.text("echo:" + request.arguments().stringOr("message", "")));
                    s.tools().register(
                            tool -> tool.name("search")
                                    .inputSchema("""
                                            {"type":"object","properties":{"region":{"type":"string","x-mcp-header":"Region"}}}
                                            """),
                            (ctx, request) -> ToolResult.text("searched"));
                });
    }

    @AfterAll
    static void stopServer() {
        server.close();
    }

    @Test
    void successBranchExposesTextContentAssertions() throws Exception {
        try (var client = McpTestClients.latest(server.port())) {
            // snips-start: testkit_assert_success
            var response = client.post("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"echo","arguments":{"message":"hi"}}}
                """);

            assertThat(response).isSuccess().hasTextContent("echo:hi");
            // snips-end: testkit_assert_success
        }
    }

    @Test
    void errorBranchExposesCodeAndMessageAssertions() throws Exception {
        try (var client = McpTestClients.latest(server.port())) {
            // snips-start: testkit_assert_error
            var response = client.post("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"missing","arguments":{}}}
                """);

            assertThatResponse(response)
                .isJsonRpcError()
                .hasErrorCode(-32602)
                .hasErrorMessage("Unknown tool: missing");
            // snips-end: testkit_assert_error
        }
    }

    @SuppressWarnings("unused")
    static void httpAssertions(HttpResponse<String> response) {
        // snips-start: testkit_assert_http
        assertThatResponse(response).hasStatus(200).isSuccess().hasTextContent("echo:hi");
        assertThatResponse(response).hasStatus(400).isJsonRpcError().hasId(9).hasErrorCode(-32020);
        assertThatResponse(response).isRejectedWith(400, "Duplicate MCP header");
        // snips-end: testkit_assert_http
    }

    @Test
    void httpStatusAndJsonRpcErrorBranchChain() throws Exception {
        try (var client = McpTestClients.latest(server.port())) {
            var success = client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"echo","arguments":{"message":"hi"}}}
                    """);
            assertThatResponse(success).hasStatus(200).isSuccess().hasTextContent("echo:hi");

            var mismatch = client.post("""
                    {"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"search","arguments":{"region":"us-west1"}}}
                    """, Map.of("Mcp-Param-Region", "eu-central1"));
            assertThatResponse(mismatch).hasStatus(400).isJsonRpcError().hasId(9).hasErrorCode(-32020);
        }
    }

    @Test
    void transportRejectionBeforeAnEnvelopeExistsIsPlainText() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2026-07-28")
                .header("MCP-Protocol-Version", "2026-07-28")
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .build();

        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThatResponse(response).isRejectedWith(400, "Duplicate MCP header");
    }
}
