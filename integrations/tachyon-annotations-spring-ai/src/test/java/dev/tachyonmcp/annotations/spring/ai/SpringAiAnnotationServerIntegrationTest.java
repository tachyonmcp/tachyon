/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.annotations.spring.ai;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.testkit.McpTestClients;
import dev.tachyonmcp.testkit.McpTestServers;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpArg;
import org.springframework.ai.mcp.annotation.McpMeta;
import org.springframework.ai.mcp.annotation.McpProgressToken;
import org.springframework.ai.mcp.annotation.McpPrompt;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;

/**
 * Starts a real Tachyon server (Netty transport, port 0) with {@link SpringAiAnnotationProvider}
 * wired through {@code ServerBuilder.annotations(...)} and exercises the annotated handlers over
 * the MCP JSON-RPC wire protocol. Spec ref: MCP 2025-11-25 tools/resources/prompts.
 */
class SpringAiAnnotationServerIntegrationTest {

    @SuppressWarnings("unused")
    static class Fixture {

        @McpTool(name = "greet", description = "Greets someone")
        String greet(@McpToolParam(description = "target name") String who) {
            return "Hello, " + who + "!";
        }

        @McpResource(uri = "test://config", name = "cfg")
        String config() {
            return "static-config";
        }

        @McpResource(uri = "test://item/{id}", name = "item")
        String item(String id) {
            return "item-" + id;
        }

        @McpPrompt(name = "story", description = "Tells a story")
        String story(String topic) {
            return "A story about " + topic;
        }
    }

    private static TachyonServer startServer() {
        return startServer(new Fixture());
    }

    private static TachyonServer startServer(Object fixture) {
        return McpTestServers.start(
                b -> b.annotations(annotations -> {
                    annotations.withProvider(new SpringAiAnnotationProvider());
                    annotations.register(fixture);
                }),
                server -> {});
    }

    @Test
    void annotatedToolIsAdvertisedAndCallableOverWire() throws Exception {
        try (var server = startServer();
                var client = McpTestClients.latest(server.port())) {
            var list = client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"tools/list"}
                    """);

            // language=json
            var expectedList = """
                    {"jsonrpc":"2.0","id":1,"result":{
                        "tools":[{
                            "name":"greet",
                            "description":"Greets someone",
                            "inputSchema":{"type":"object",
                                "properties":{"who":{"type":"string","description":"target name"}},
                                "required":["who"]},
                            "annotations":{"readOnlyHint":false,
                                "destructiveHint":true,
                                "idempotentHint":false,
                                "openWorldHint":true}
                        }],
                        "resultType":"complete","ttlMs":0,"cacheScope":"public"}
                    }
                    """;
            assertThatJson(list.body()).isEqualTo(expectedList);

            var call = client.post("""
                    {"jsonrpc":"2.0","id":2,"method":"tools/call",
                     "params":{"name":"greet","arguments":{"who":"Ada"}}}
                    """);

            // language=json
            var expectedCall = """
                    {"jsonrpc":"2.0","id":2,"result":{
                        "content":[{"type":"text","text":"Hello, Ada!"}],
                        "resultType":"complete"}
                    }
                    """;
            assertThatJson(call.body()).isEqualTo(expectedCall);
        }
    }

    @Test
    void staticAndTemplatedResourcesAreReadableOverWire() throws Exception {
        try (var server = startServer();
                var client = McpTestClients.latest(server.port())) {
            var read = client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"resources/read",
                     "params":{"uri":"test://config"}}
                    """);

            // language=json
            var expectedRead = """
                    {"jsonrpc":"2.0","id":1,"result":{
                        "contents":[{"uri":"test://config","mimeType":"text/plain","text":"static-config"}],
                        "resultType":"complete","ttlMs":0,"cacheScope":"public"}
                    }
                    """;
            assertThatJson(read.body()).isEqualTo(expectedRead);

            var templated = client.post("""
                    {"jsonrpc":"2.0","id":2,"method":"resources/read",
                     "params":{"uri":"test://item/42"}}
                    """);

            // language=json
            var expectedTemplated = """
                    {"jsonrpc":"2.0","id":2,"result":{
                        "contents":[{"uri":"test://item/42","mimeType":"text/plain","text":"item-42"}],
                        "resultType":"complete","ttlMs":0,"cacheScope":"public"}
                    }
                    """;
            assertThatJson(templated.body()).isEqualTo(expectedTemplated);
        }
    }

    @Test
    void annotatedPromptReturnsMessagesOverWire() throws Exception {
        try (var server = startServer();
                var client = McpTestClients.latest(server.port())) {
            var get = client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"prompts/get",
                     "params":{"name":"story","arguments":{"topic":"cats"}}}
                    """);

            // language=json
            var expected = """
                    {"jsonrpc":"2.0","id":1,"result":{
                        "description":"Tells a story",
                        "messages":[{"role":"user",
                            "content":{"type":"text","text":"A story about cats"}}],
                        "resultType":"complete"}
                    }
                    """;
            assertThatJson(get.body()).isEqualTo(expected);
        }
    }

    @SuppressWarnings("unused")
    static class SyncRequestContextFixture {
        @McpTool(name = "withSyncRequestContext")
        String withSyncRequestContext(McpSyncRequestContext requestContext) {
            return "unreachable";
        }
    }

    @Test
    void syncRequestContextParameterFailsServerStartupNotInvocation() {
        assertThatThrownBy(() -> startServer(new SyncRequestContextFixture()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("McpSyncRequestContext")
                .hasMessageContaining("withSyncRequestContext");
    }

    @SuppressWarnings("unused")
    static class UnsupportedContextFixture {
        @McpTool
        String needsMeta(McpMeta meta) {
            return "unreachable";
        }
    }

    @Test
    void unsupportedSpecialParameterTypeFailsServerStartupNotInvocation() {
        assertThatThrownBy(() -> startServer(new UnsupportedContextFixture()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("McpMeta")
                .hasMessageContaining("needsMeta");
    }

    @SuppressWarnings("unused")
    static class McpArgFixture {
        @McpPrompt(name = "storyWithArg")
        String storyWithArg(@McpArg(name = "topicName", description = "the topic") String topic) {
            return "Story: " + topic;
        }
    }

    @Test
    void mcpArgOverridesArgumentNameDescriptionAndRequiredOverWire() throws Exception {
        try (var server = startServer(new McpArgFixture());
                var client = McpTestClients.latest(server.port())) {
            var list = client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"prompts/list"}
                    """);

            // language=json
            var expectedList = """
                    {"jsonrpc":"2.0","id":1,"result":{
                        "prompts":[{
                            "name":"storyWithArg",
                            "arguments":[{"name":"topicName","description":"the topic","required":false}]
                        }],
                        "resultType":"complete","ttlMs":0,"cacheScope":"public"}
                    }
                    """;
            assertThatJson(list.body()).isEqualTo(expectedList);

            var get = client.post("""
                    {"jsonrpc":"2.0","id":2,"method":"prompts/get",
                     "params":{"name":"storyWithArg","arguments":{"topicName":"dragons"}}}
                    """);

            // language=json
            var expectedGet = """
                    {"jsonrpc":"2.0","id":2,"result":{
                        "messages":[{"role":"user",
                            "content":{"type":"text","text":"Story: dragons"}}],
                        "resultType":"complete"}
                    }
                    """;
            assertThatJson(get.body()).isEqualTo(expectedGet);
        }
    }

    record Point(int x, int y) {}

    @SuppressWarnings("unused")
    static class CompositeResultFixture {
        @McpTool(name = "makePoint")
        Point makePoint() {
            return new Point(3, 4);
        }
    }

    @Test
    void compositeReturnValueBecomesStructuredContentNotAToStringDumpOverWire() throws Exception {
        try (var server = startServer(new CompositeResultFixture());
                var client = McpTestClients.latest(server.port())) {
            var call = client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"tools/call",
                     "params":{"name":"makePoint","arguments":{}}}
                    """);

            // language=json
            var expected = """
                    {"jsonrpc":"2.0","id":1,"result":{
                        "content":[{"type":"text","text":"{\\"x\\":3,\\"y\\":4}"}],
                        "structuredContent":{"x":3,"y":4},
                        "resultType":"complete"}
                    }
                    """;
            assertThatJson(call.body()).isEqualTo(expected);
        }
    }

    @SuppressWarnings("unused")
    static class TransportContextFixture {
        @McpTool(name = "withTransportContext")
        String withTransportContext(McpTransportContext ctx) {
            return "unreachable";
        }
    }

    @Test
    void transportContextParameterFailsServerStartupNotInvocation() {
        assertThatThrownBy(() -> startServer(new TransportContextFixture()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("McpTransportContext")
                .hasMessageContaining("withTransportContext");
    }

    @SuppressWarnings("unused")
    static class ServerExchangeFixture {
        @McpTool(name = "withExchange")
        String withExchange(McpSyncServerExchange exchange) {
            return "unreachable";
        }
    }

    @Test
    void serverExchangeParameterFailsServerStartupNotInvocation() {
        assertThatThrownBy(() -> startServer(new ServerExchangeFixture()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("McpSyncServerExchange")
                .hasMessageContaining("withExchange");
    }

    @SuppressWarnings("unused")
    static class CallToolRequestFixture {
        @McpTool(name = "withCallToolRequest")
        String withCallToolRequest(McpSchema.CallToolRequest request) {
            return "unreachable";
        }
    }

    @Test
    void callToolRequestParameterFailsServerStartupNotInvocation() {
        assertThatThrownBy(() -> startServer(new CallToolRequestFixture()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CallToolRequest")
                .hasMessageContaining("withCallToolRequest");
    }

    @SuppressWarnings("unused")
    static class ProgressTokenFixture {
        @McpTool(name = "withProgressToken")
        String withProgressToken(@McpProgressToken String token) {
            return "unreachable";
        }
    }

    @Test
    void progressTokenParameterFailsServerStartupNotInvocation() {
        assertThatThrownBy(() -> startServer(new ProgressTokenFixture()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("McpProgressToken")
                .hasMessageContaining("withProgressToken");
    }

    @SuppressWarnings("unused")
    static class PrivateMethodFixture {
        @McpTool(name = "privateTool")
        private String privateTool() {
            return "unreachable";
        }
    }

    @Test
    void privateAnnotatedMethodFailsServerStartupNotInvocation() {
        assertThatThrownBy(() -> startServer(new PrivateMethodFixture()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("private")
                .hasMessageContaining("privateTool");
    }

    interface Operation<T> {
        @McpTool(name = "run", description = "Runs an operation")
        String run(@McpToolParam(description = "the input") T input);
    }

    static class StringOperation implements Operation<String> {
        @Override
        public String run(String input) {
            return "ran " + input;
        }
    }

    /** The annotation sits on {@code Operation<T>}; the schema and the call must use {@code String}, not the bare {@code T}. */
    @Test
    void toolDeclaredOnAGenericInterfaceUsesTheTypeItsImplementationBinds() throws Exception {
        try (var server = startServer(new StringOperation());
                var client = McpTestClients.latest(server.port())) {
            var list = client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"tools/list"}
                    """);

            // language=json
            var expectedList = """
                    {"jsonrpc":"2.0","id":1,"result":{
                        "tools":[{
                            "name":"run",
                            "description":"Runs an operation",
                            "inputSchema":{"type":"object",
                                "properties":{"input":{"type":"string","description":"the input"}},
                                "required":["input"]},
                            "annotations":{"readOnlyHint":false,
                                "destructiveHint":true,
                                "idempotentHint":false,
                                "openWorldHint":true}
                        }],
                        "resultType":"complete","ttlMs":0,"cacheScope":"public"}
                    }
                    """;
            assertThatJson(list.body()).isEqualTo(expectedList);

            var call = client.post("""
                    {"jsonrpc":"2.0","id":2,"method":"tools/call",
                     "params":{"name":"run","arguments":{"input":"x"}}}
                    """);

            // language=json
            var expectedCall = """
                    {"jsonrpc":"2.0","id":2,"result":{
                        "content":[{"type":"text","text":"ran x"}],
                        "resultType":"complete"}
                    }
                    """;
            assertThatJson(call.body()).isEqualTo(expectedCall);
        }
    }
}
