package dev.tachyonmcp.docs.testkit;

// snips-start: testkit_echo_tool_test
import static dev.tachyonmcp.testkit.JsonRpcResponseAssert.assertThat;
import static dev.tachyonmcp.testkit.McpHttpResponseAssert.assertThatResponse;

import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.testkit.McpTestClients;
import dev.tachyonmcp.testkit.McpTestServers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class EchoToolTest {

    private static TachyonServer server;

    @BeforeAll
    static void startServer() {
        server = McpTestServers.start(
                b -> {},
                s -> s.tools()
                        .register(
                                tool -> tool.name("echo").description("Echo back the message"),
                                (ctx, request) -> ToolResult.text(
                                        "echo:" + request.arguments().stringOr("message", ""))));
    }

    @AfterAll
    static void stopServer() {
        server.close();
    }

    @Test
    void echoesMessage() throws Exception {
        try (var client = McpTestClients.latest(server.port())) {
            var response = client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"tools/call",
                     "params":{"name":"echo","arguments":{"message":"hi"}}}
                    """);

            assertThatResponse(response).hasStatus(200).isSuccess().hasTextContent("echo:hi");
        }
    }

    @Test
    void rejectsUnknownTool() throws Exception {
        try (var client = McpTestClients.latest(server.port())) {
            var response = client.post("""
                    {"jsonrpc":"2.0","id":2,"method":"tools/call",
                     "params":{"name":"missing","arguments":{}}}
                    """);

            assertThat(response).isJsonRpcError().hasErrorCode(-32602).hasErrorMessage("Unknown tool: missing");
        }
    }
}
// snips-end: testkit_echo_tool_test
