package dev.tachyonmcp.docs.features.tools;

import static dev.tachyonmcp.docs.ForkedMain.DOCUMENTED_PORT;
import static dev.tachyonmcp.docs.JsonRpc.items;
import static dev.tachyonmcp.docs.JsonRpc.named;
import static dev.tachyonmcp.docs.JsonRpc.names;
import static dev.tachyonmcp.docs.JsonRpc.post;
import static dev.tachyonmcp.docs.JsonRpc.request;
import static dev.tachyonmcp.docs.JsonRpc.result;
import static dev.tachyonmcp.docs.JsonRpc.text;
import static dev.tachyonmcp.testkit.McpHttpResponseAssert.assertThatResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.docs.ForkedMain;
import dev.tachyonmcp.testkit.McpTestClients;
import dev.tachyonmcp.testkit.McpTestServers;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

class ToolsDocsTest {

    private static TachyonServer startWith(Object service) {
        return McpTestServers.start(b -> b.annotations(a -> a.register(service)), s -> {});
    }

    private static JsonNode call(TachyonServer server, String name, String arguments) throws Exception {
        return result(server, "tools/call", "{\"name\":\"%s\",\"arguments\":%s}".formatted(name, arguments));
    }

    @Test
    void mainServesTheAnnotatedGreetTool() throws Exception {
        try (var ignored = ForkedMain.start("dev.tachyonmcp.docs.features.tools.GreetingServer")) {
            var greet = named(result(DOCUMENTED_PORT, "tools/list"), "tools", "greet");

            assertThat(greet.path("description").asString()).isEqualTo("Say hello to someone");
            assertThat(text(result(DOCUMENTED_PORT, "tools/call", """
                    {"name":"greet","arguments":{"name":"Ada"}}
                    """))).isEqualTo("Hello, Ada!");
        }
    }

    @Test
    void nullableParameterIsOptionalAndTheRestAreRequired() throws Exception {
        try (var server = startWith(new WelcomeService())) {
            var schema = named(result(server, "tools/list"), "tools", "welcome").path("inputSchema");

            assertThat(items(schema.path("required"))).extracting(JsonNode::asString).containsExactly("name");
            assertThat(text(call(server, "welcome", "{\"name\":\"Ada\"}"))).isEqualTo("Hello, Ada!");
            assertThat(text(call(server, "welcome", "{\"name\":\"Ada\",\"title\":\"Dr.\"}")))
                    .isEqualTo("Hello, Dr. Ada!");
        }
    }

    @Test
    void recordParameterReceivesTheWholeArgumentsAndRecordResultIsStructured() throws Exception {
        try (var server = startWith(new ForecastService())) {
            var tool = named(result(server, "tools/list"), "tools", "forecast");
            assertThat(tool.path("inputSchema").path("properties").propertyNames())
                    .as("no request wrapper")
                    .containsExactlyInAnyOrder("city", "days");
            assertThat(tool.path("outputSchema").path("properties").propertyNames())
                    .containsExactlyInAnyOrder("city", "days", "highC");

            var forecast = call(server, "forecast", "{\"city\":\"Paris\",\"days\":3}");
            assertThat(forecast.path("structuredContent").path("city").asString()).isEqualTo("Paris");
            assertThat(forecast.path("structuredContent").path("days").asInt()).isEqualTo(3);
            assertThat(forecast.path("structuredContent").path("highC").asDouble()).isEqualTo(24.0);
            assertThat(text(forecast)).contains("\"highC\":24.0");
        }
    }

    @Test
    void errorResultIsAToolErrorWhileInvalidArgumentsAreJsonRpcErrors() throws Exception {
        try (var server = startWith(new GreetingWithErrors())) {
            var blank = call(server, "greet", "{\"name\":\"  \"}");
            assertThat(blank.path("isError").asBoolean()).isTrue();
            assertThat(text(blank)).isEqualTo("Provide a non-blank name.");
            assertThat(text(call(server, "greet", "{\"name\":\"Ada\"}"))).isEqualTo("Hello, Ada!");

            for (var arguments : new String[] {"{}", "{\"name\":42}"}) {
                assertThatResponse(post(server, "tools/call", "{\"name\":\"greet\",\"arguments\":%s}".formatted(arguments)))
                        .hasStatus(400)
                        .isJsonRpcError()
                        .hasErrorCode(-32602);
            }
            assertThatResponse(post(server, "tools/call", "{\"name\":\"nope\",\"arguments\":{}}"))
                    .isJsonRpcError()
                    .hasErrorCode(-32602)
                    .hasErrorMessageContaining("Unknown tool: nope");
        }
    }

    @Test
    void metadataAppearsInTheResponseMeta() throws Exception {
        try (var server = startWith(new MetaTool())) {
            var finished = call(server, "finish", "{}");

            assertThat(text(finished)).isEqualTo("done");
            assertThat(finished.path("_meta").path("taskId").asString()).isEqualTo("t-123");
        }
    }

    @Test
    void programmaticRegistrationsServeTheirHandlers() throws Exception {
        try (var hello = ProgrammaticTools.hello()) {
            assertThat(text(call(hello, "hello", "{}"))).isEqualTo("Hello!");
        }
        try (var schema = ProgrammaticTools.helloWithSchema()) {
            var tool = named(result(schema, "tools/list"), "tools", "hello");
            assertThat(tool.path("inputSchema").path("properties").path("name").path("type").asString())
                    .isEqualTo("string");
            assertThat(text(call(schema, "hello", "{}"))).isEqualTo("Hello, world!");
            assertThat(text(call(schema, "hello", "{\"name\":\"Ada\"}"))).isEqualTo("Hello, Ada!");
        }
        try (var async = ProgrammaticTools.asyncWeather()) {
            assertThat(text(call(async, "get_weather_async", "{\"city\":\"Paris\"}"))).isEqualTo("Sunny in Paris");
        }
    }

    @Test
    void typedToolDecodesInputAndDerivesBothSchemas() throws Exception {
        try (var server = TypedForecastTool.start()) {
            var tool = named(result(server, "tools/list"), "tools", "get_forecast");
            assertThat(tool.path("inputSchema").path("properties").propertyNames())
                    .containsExactlyInAnyOrder("city", "days");
            assertThat(tool.path("outputSchema").path("properties").propertyNames())
                    .containsExactlyInAnyOrder("summary", "highC");

            var forecast = call(server, "get_forecast", "{\"city\":\"Paris\",\"days\":3}");
            assertThat(forecast.path("structuredContent").path("summary").asString()).isEqualTo("Sunny in Paris");
            assertThat(forecast.path("structuredContent").path("highC").asDouble()).isEqualTo(23.0);
        }
    }

    @Test
    void mirroredArgumentMustMatchItsHeader() throws Exception {
        try (var server = HeaderTool.start(HeaderTool.schema());
                var client = McpTestClients.latest(server.port())) {
            var body = request("tools/call", """
                    {"name":"search","arguments":{"region":"us-west1","query":"cats"}}
                    """);

            assertThatResponse(client.post(body)).hasStatus(400).isJsonRpcError().hasErrorCode(-32020);
            assertThatResponse(client.post(body, Map.of("Mcp-Param-Region", "eu-central1")))
                    .hasStatus(400)
                    .isJsonRpcError()
                    .hasErrorCode(-32020);
            assertThatResponse(client.post(body, Map.of("Mcp-Param-Region", "us-west1")))
                    .hasStatus(200)
                    .isSuccess();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\",\"x-mcp-header\":\"bad name\"}}}",
        "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\",\"x-mcp-header\":\"Region\"},\"b\":{\"type\":\"string\",\"x-mcp-header\":\"region\"}}}",
        "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"number\",\"x-mcp-header\":\"Amount\"}}}",
        "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"object\",\"properties\":{\"b\":{\"type\":\"string\",\"x-mcp-header\":\"Nested\"}}}}}"
    })
    void invalidHeaderAnnotationsFailRegistration(String schema) {
        assertThatThrownBy(() -> HeaderTool.start(schema)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Timeout(60)
    void longHandlerCanStopWhenNobodyIsWaitingForTheResponse() throws Exception {
        ReindexTool.REINDEXED.set(0);
        try (var server = McpTestServers.start(b -> {}, ReindexTool::register)) {
            var finished = text(call(server, "reindex", "{}"));
            assertThat(finished).isEqualTo("done");
            assertThat(ReindexTool.REINDEXED).hasValue(ReindexTool.BATCH_COUNT);

            ReindexTool.REINDEXED.set(0);
            try (var socket = new Socket("127.0.0.1", server.port())) {
                var body = """
                        {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"reindex","arguments":{},
                         "_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28",
                                  "io.modelcontextprotocol/clientInfo":{"name":"t","version":"1"},
                                  "io.modelcontextprotocol/clientCapabilities":{}}}}
                        """;
                var payload = body.getBytes(StandardCharsets.UTF_8);
                var head = "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:%d\r\nContent-Type: application/json\r\nAccept: application/json, text/event-stream\r\nMCP-Protocol-Version: 2026-07-28\r\nMcp-Method: tools/call\r\nMcp-Name: reindex\r\nContent-Length: %d\r\n\r\n"
                        .formatted(server.port(), payload.length);
                socket.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().write(payload);
                socket.getOutputStream().flush();
                await().atMost(Duration.ofSeconds(10)).until(() -> ReindexTool.REINDEXED.get() >= 2);
            } catch (IOException e) {
                throw new AssertionError(e);
            }

            await().atMost(Duration.ofSeconds(20)).until(() -> {
                var before = ReindexTool.REINDEXED.get();
                Thread.sleep(300);
                return before == ReindexTool.REINDEXED.get();
            });
            assertThat(ReindexTool.REINDEXED.get()).as("stopped at a safe point").isLessThan(ReindexTool.BATCH_COUNT);
        }
    }
}
