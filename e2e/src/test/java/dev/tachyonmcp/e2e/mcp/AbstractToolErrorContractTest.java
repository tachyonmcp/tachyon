/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp;

import static dev.tachyonmcp.testkit.McpHttpResponseAssert.assertThatResponse;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.testkit.McpClient;
import org.junit.jupiter.api.Test;

/**
 * Tool error handling scenarios that hold under both MCP protocol revisions: exception mapping to
 * JSON-RPC error codes ({@code -32603} for handler failure, {@code -32602} for invalid params) and
 * message redaction. Only the client creation differs, supplied by subclasses in {@code
 * v2025_11_25}/{@code v2026_07_28}.
 */
public abstract class AbstractToolErrorContractTest<C extends McpClient> extends AbstractStatelessMcpE2eTest<C> {

    /** Returns a client ready to send requests (handshake already performed, if the version needs one). */
    protected abstract C readyClient() throws Exception;

    /** Returns the HTTP status this protocol revision ties to an {@code -32602} invalid-params error. */
    protected abstract int invalidParamsHttpStatus();

    @Override
    protected void startDefaultServer() {
        startServerWith(s -> s.tools()
                .register(
                        b -> b.name("boom").description("Throws after sending a notification"), (context, request) -> {
                            context.notifications().comment("before-boom");
                            throw new RuntimeException("Simulated handler failure. Ignore it");
                        }));
    }

    @Test
    void toolThrowsAfterSseUpgradeStillClosesStream() throws Exception {
        try (var client = readyClient()) {
            var body = """
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"boom","arguments":{}}}
                """;
            var response = client.post(body);

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains(": before-boom");
            // language=json
            assertThat(response.body()).contains("""
                {"jsonrpc":"2.0","id":1,"error":{"code":-32603,"message":"Tool handler failed"}}
                """.trim());
        }
    }

    @Test
    void shouldRejectUnknownTool() throws Exception {
        try (var client = readyClient()) {
            var response = client.post("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"missing","arguments":{}}}
                """);

            assertThatResponse(response)
                    .hasStatus(invalidParamsHttpStatus())
                    .isJsonRpcError()
                    .hasId(3)
                    .hasErrorCode(-32602)
                    .hasErrorMessage("Unknown tool: missing");
        }
    }

    @Test
    void shouldRedactIllegalArgumentExceptionFromInvalidParamsError() throws Exception {
        startServerWith(s -> s.tools()
                .register(builder -> builder.name("bad-arg").description("Rejects input"), (context, request) -> {
                    throw new IllegalArgumentException("sensitive internal detail");
                }));

        try (var client = readyClient()) {
            var response = client.post("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"bad-arg","arguments":{}}}
                """);
            assertThatResponse(response)
                    .hasStatus(invalidParamsHttpStatus())
                    .isJsonRpcError()
                    .hasId(2)
                    .hasErrorCode(-32602)
                    .hasErrorMessage("Invalid params");
            assertThat(response.body()).doesNotContain("sensitive internal detail");
        }
    }
}
