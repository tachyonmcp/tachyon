/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp;

import static dev.tachyonmcp.testkit.JsonRpcResponseAssert.assertThat;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.testkit.McpClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Every pipeline stage must agree on which requests hit the configured MCP endpoint
 * ({@code NetworkConfig#endpointPath}): a request the endpoint validator admits gets the full
 * protocol treatment (dispatch, Accept rules, version negotiation), and anything else is a
 * {@code 404}. Subclasses in {@code v2025_11_25}/{@code v2026_07_28} pick the configured path, the
 * path requests go to, and the protocol revision.
 */
public abstract class AbstractEndpointPathTest<C extends McpClient> extends AbstractMcpE2eTest<C> {

    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    /** Returns the endpoint path the server is configured with. */
    protected abstract String configuredEndpointPath();

    /** Returns the path requests are sent to; must be one the configured endpoint serves. */
    protected abstract String requestPath();

    /** Returns the {@code MCP-Protocol-Version} this revision's requests carry. */
    protected abstract String protocolVersion();

    /** Opens a session when the revision has one, returning its id. */
    protected abstract @Nullable String openSession(C client) throws Exception;

    protected abstract C createTestClient(URI endpoint);

    @Override
    protected final C createTestClient() {
        return createTestClient(port);
    }

    @Override
    protected final C createTestClient(int port) {
        return createTestClient(URI.create("http://localhost:" + port + requestPath()));
    }

    @Override
    protected void startDefaultServer() {
        startServer(
                b -> b.capabilities(c -> c.tools()).network(n -> n.endpointPath(configuredEndpointPath())),
                s -> s.tools()
                        .register(
                                d -> d.name("echo").description("Echoes its text argument"),
                                (ctx, request) ->
                                        ToolResult.text(request.arguments().stringOr("text", ""))));
    }

    private HttpResponse<String> post(String path, String accept, String version, String body) throws Exception {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("Accept", accept)
                .header("MCP-Protocol-Version", version)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        return HTTP_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void toolCallOnEndpointReturnsResult() throws Exception {
        try (var client = createTestClient()) {
            var sessionId = openSession(client);

            // language=JSON
            var response = client.sendRpc(sessionId, """
                    {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"echo","arguments":{"text":"hi"}}}
                    """);

            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(response)
                    .isSuccess()
                    .hasId(7)
                    .hasContentExactly(JsonNodeFactory.instance
                            .objectNode()
                            .put("type", "text")
                            .put("text", "hi"));
        }
    }

    /** MCP Streamable HTTP: a POST must accept both {@code application/json} and {@code text/event-stream}. */
    @Test
    void postWithoutEventStreamAcceptIsNotAcceptable() throws Exception {
        // language=JSON
        var response = post(requestPath(), "application/json", protocolVersion(), """
                {"jsonrpc":"2.0","id":1,"method":"ping"}
                """);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(406);
        assertThat(response.body())
                .isEqualTo("Accept header must include application/json or text/event-stream on POST");
    }

    /** SEP-2575: an unknown version is rejected with the request's id echoed back. */
    @Test
    void unsupportedProtocolVersionEchoesRequestId() throws Exception {
        // language=JSON
        var response = post(requestPath(), "application/json, text/event-stream", "v999", """
                {"jsonrpc":"2.0","id":1,"method":"ping"}
                """);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        // language=JSON
        assertThatJson(response.body()).isEqualTo("""
                {
                  "jsonrpc": "2.0",
                  "id": 1,
                  "error": {
                    "code": -32022,
                    "message": "Unsupported protocol version",
                    "data": {
                      "supported": ["2026-07-28", "2025-11-25"],
                      "requested": "v999"
                    }
                  }
                }
                """);
    }

    private List<String> foreignPaths() {
        var path = configuredEndpointPath().endsWith("/")
                ? configuredEndpointPath().substring(0, configuredEndpointPath().length() - 1)
                : configuredEndpointPath();
        return List.of("/", path + "x", path + "/extra", "/other" + path);
    }

    @ParameterizedTest
    @MethodSource("foreignPaths")
    void foreignPathIsNotFound(String path) throws Exception {
        // language=JSON
        var response = post(path, "application/json, text/event-stream", protocolVersion(), """
                {"jsonrpc":"2.0","id":1,"method":"ping"}
                """);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(404);
    }
}
