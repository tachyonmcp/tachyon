package dev.tachyonmcp.docs.quickstart;

import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.testkit.McpTestClients;
import dev.tachyonmcp.testkit.McpTestServers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static dev.tachyonmcp.testkit.McpHttpResponseAssert.assertThatResponse;
import static org.assertj.core.api.Assertions.assertThat;

class MyMcpServerTest {

    private static TachyonServer server;

    @BeforeAll
    static void startServer() {
        server = McpTestServers.start(
                b -> b.annotations(a -> a.register(new MyMcpServer.GreetingService())), s -> {});
    }

    @AfterAll
    static void stopServer() {
        server.close();
    }

    @Test
    void greetsByNameWithTheResponseShownInTheDocs() throws Exception {
        try (var client = McpTestClients.latest(server.port())) {
            var response = client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"tools/call",
                     "params":{"name":"greet","arguments":{"name":"Ada"}}}
                    """);

            assertThatResponse(response)
                    .hasStatus(200)
                    .isSuccess()
                    .hasId(1)
                    .hasResultType("complete")
                    .hasTextContent("Hello, Ada!")
                    .hasResult("""
                            {"content":[{"type":"text","text":"Hello, Ada!"}],"resultType":"complete"}
                            """);
        }
    }

    @Test
    void greetingFollowsTheArgument() throws Exception {
        try (var client = McpTestClients.latest(server.port())) {
            var response = client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"tools/call",
                     "params":{"name":"greet","arguments":{"name":"Grace"}}}
                    """);

            assertThatResponse(response).hasStatus(200).isSuccess().hasTextContent("Hello, Grace!");
        }
    }

    @Test
    void deriveSchemaFromMethodSignature() throws Exception {
        try (var client = McpTestClients.latest(server.port())) {
            var response = client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"tools/list"}
                    """);

            var tools = assertThatResponse(response).hasStatus(200).isSuccess().result().path("tools");

            assertThat(tools).hasSize(1);
            var greet = tools.get(0);
            assertThat(greet.path("name").asString()).isEqualTo("greet");
            assertThat(greet.path("description").asString()).isEqualTo("Say hello to someone");
            var schema = greet.path("inputSchema");
            assertThat(schema.path("type").asString()).isEqualTo("object");
            assertThat(schema.path("properties").path("name").path("type").asString())
                    .isEqualTo("string");
            assertThat(schema.path("required")).extracting(n -> n.asString()).containsExactly("name");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"name\":42}"})
    void rejectsInvalidArgumentsWithInvalidParams(String arguments) throws Exception {
        try (var client = McpTestClients.latest(server.port())) {
            var response = client.post("""
                    {"jsonrpc":"2.0","id":7,"method":"tools/call",
                     "params":{"name":"greet","arguments":%s}}
                    """.formatted(arguments));

            assertThatResponse(response)
                    .hasStatus(400)
                    .isJsonRpcError()
                    .hasId(7)
                    .hasErrorCode(-32602);
        }
    }
}
