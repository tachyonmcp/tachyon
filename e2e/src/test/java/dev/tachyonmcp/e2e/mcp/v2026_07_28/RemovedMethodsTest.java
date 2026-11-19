/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2026_07_28;

import static dev.tachyonmcp.testkit.JsonRpcResponseAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.e2e.mcp.AbstractStatelessMcpE2eTest;
import dev.tachyonmcp.testkit.Mcp20260728Client;
import dev.tachyonmcp.testkit.McpClient;
import dev.tachyonmcp.testkit.McpTestClients;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * MCP 2026-07-28 removed {@code initialize}, {@code ping}, {@code logging/setLevel},
 * {@code resources/subscribe}, and {@code resources/unsubscribe} (SEP-2575): a server implementing
 * only this revision must answer them with HTTP 404 and JSON-RPC {@code -32601} (Method not found),
 * not dispatch them as it would under 2025-11-25.
 */
class RemovedMethodsTest extends AbstractStatelessMcpE2eTest<McpClient> {

    @Override
    protected Mcp20260728Client createTestClient() {
        return createTestClient(port);
    }

    @Override
    protected Mcp20260728Client createTestClient(int port) {
        return McpTestClients.latest(port);
    }

    @ParameterizedTest
    @ValueSource(strings = {"initialize", "ping", "logging/setLevel", "resources/subscribe", "resources/unsubscribe"})
    @DisplayName("[SEP-2575] methods removed in 2026-07-28 answer 404 and -32601")
    void removedMethodReturns404MethodNotFound(String method) throws Exception {
        var body = "{\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"%s\",\"params\":{}}".formatted(method);
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2026-07-28")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode())
                .as("status for removed method '%s': %s", method, response.body())
                .isEqualTo(404);
        assertThat(response).isJsonRpcError().hasId(42).hasErrorCode(-32601);
    }
}
