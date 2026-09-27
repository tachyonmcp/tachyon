/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.testkit;

import static dev.tachyonmcp.testkit.JsonRpcResponseAssert.assertThat;
import static dev.tachyonmcp.testkit.McpHttpResponseAssert.assertThatResponse;

import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.TachyonServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Mirrors the complete JUnit example in {@code docs/testkit.md}; keep both in sync. */
class EchoToolDocsExampleTest {

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
