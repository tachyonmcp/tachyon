/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.annotations.mcpjava;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;

import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.testkit.McpTestClients;
import dev.tachyonmcp.testkit.McpTestServers;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mcpjava.server.content.Annotations;
import org.mcpjava.server.content.ContentBlock;
import org.mcpjava.server.content.TextContent;
import org.mcpjava.server.prompts.Prompt;
import org.mcpjava.server.prompts.PromptArg;
import org.mcpjava.server.resources.Resource;
import org.mcpjava.server.resources.ResourceTemplate;
import org.mcpjava.server.resources.ResourceTemplateArg;
import org.mcpjava.server.tools.Tool;
import org.mcpjava.server.tools.ToolArg;
import org.mcpjava.server.tools.ToolResponse;

/**
 * Starts a real Tachyon server (Netty transport, port 0) with {@link McpJavaAnnotationProvider}
 * wired through {@code ServerBuilder.annotations(...)} and exercises the annotated handlers over
 * the MCP JSON-RPC wire protocol. Spec ref: MCP 2025-11-25 tools/resources/prompts.
 */
class McpJavaAnnotationServerIntegrationTest {

    @SuppressWarnings("unused")
    static class Fixture {

        @Tool(name = "greet", description = "Greets someone")
        String greet(@ToolArg(name = "who", description = "target name") String who) {
            return "Hello, " + who + "!";
        }

        @Resource(uri = "test://config", name = "cfg", mimeType = "application/json")
        String config() {
            return "{\"env\":\"test\"}";
        }

        @ResourceTemplate(uriTemplate = "test://item/{id}", name = "item")
        String item(@ResourceTemplateArg(name = "id") String id) {
            return "item-" + id;
        }

        @Prompt(name = "story", description = "Tells a story")
        String story(@PromptArg(name = "topic") String topic) {
            return "A story about " + topic;
        }
    }

    private static TachyonServer startServer() {
        return startServer(new Fixture());
    }

    private static TachyonServer startServer(Object fixture) {
        return McpTestServers.start(
                b -> b.annotations(annotations -> {
                    annotations.withProvider(McpJavaAnnotationProvider.instance());
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
    void staticResourceAndUriTemplateAreReadableOverWire() throws Exception {
        try (var server = startServer();
                var client = McpTestClients.latest(server.port())) {
            var read = client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"resources/read",
                     "params":{"uri":"test://config"}}
                    """);

            // language=json
            var expectedRead = """
                    {"jsonrpc":"2.0","id":1,"result":{
                        "contents":[{"uri":"test://config","mimeType":"application/json","text":"{\\"env\\":\\"test\\"}"}],
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
                        "contents":[{"uri":"test://item/42","text":"item-42"}],
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
    static class OptionalArgFixture {
        @Tool(name = "optionalGreet")
        String optionalGreet(@ToolArg(name = "who", required = false) String who) {
            return "Hi " + (who == null ? "there" : who);
        }
    }

    @Test
    void toolArgRequiredFalseIsOmittedFromRequiredAndArgMayBeSkippedOnTheWire() throws Exception {
        try (var server = startServer(new OptionalArgFixture());
                var client = McpTestClients.latest(server.port())) {
            var list = client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"tools/list"}
                    """);

            // language=json
            var expectedList = """
                    {"jsonrpc":"2.0","id":1,"result":{
                        "tools":[{
                            "name":"optionalGreet",
                            "inputSchema":{"type":"object","properties":{"who":{"type":"string"}}},
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
                     "params":{"name":"optionalGreet","arguments":{}}}
                    """);

            // language=json
            var expectedCall = """
                    {"jsonrpc":"2.0","id":2,"result":{
                        "content":[{"type":"text","text":"Hi there"}],
                        "resultType":"complete"}
                    }
                    """;
            assertThatJson(call.body()).isEqualTo(expectedCall);
        }
    }

    @SuppressWarnings("unused")
    static class ContextResourceFixture {
        @Resource(uri = "test://ctx", name = "ctxres")
        String withContext(InteractionContext ctx) {
            return ctx == null ? "no-ctx" : "has-ctx";
        }
    }

    @Test
    void staticResourceMethodReceivesInteractionContextOverWire() throws Exception {
        try (var server = startServer(new ContextResourceFixture());
                var client = McpTestClients.latest(server.port())) {
            var read = client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"resources/read",
                     "params":{"uri":"test://ctx"}}
                    """);

            // language=json
            var expected = """
                    {"jsonrpc":"2.0","id":1,"result":{
                        "contents":[{"uri":"test://ctx","text":"has-ctx"}],
                        "resultType":"complete","ttlMs":0,"cacheScope":"public"}
                    }
                    """;
            assertThatJson(read.body()).isEqualTo(expected);
        }
    }

    /** Minimal {@link ToolResponse} double — mcp-java's real factories require an SPI provider not on this test classpath. */
    private record FakeToolResponse(List<ContentBlock> content, Optional<Object> structuredContent, boolean isError)
            implements ToolResponse {
        @Override
        public Map<String, Object> metadata() {
            return Map.of();
        }
    }

    private record FakeTextContent(String text) implements TextContent {
        @Override
        public Optional<Annotations> annotations() {
            return Optional.empty();
        }

        @Override
        public Map<String, Object> metadata() {
            return Map.of();
        }
    }

    @SuppressWarnings("unused")
    static class RichResultFixture {
        @Tool(name = "richResult")
        ToolResponse richResult() {
            return new FakeToolResponse(
                    List.of(new FakeTextContent("native-text")), Optional.of(Map.of("k", "v")), false);
        }

        @Tool(name = "richError")
        ToolResponse richError() {
            return new FakeToolResponse(List.of(new FakeTextContent("boom")), Optional.empty(), true);
        }
    }

    @Test
    void mcpJavaNativeToolResponseIsTranslatedPreservingContentAndStructuredValueOverWire() throws Exception {
        try (var server = startServer(new RichResultFixture());
                var client = McpTestClients.latest(server.port())) {
            var call = client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"tools/call",
                     "params":{"name":"richResult","arguments":{}}}
                    """);

            // language=json
            var expected = """
                    {"jsonrpc":"2.0","id":1,"result":{
                        "content":[{"type":"text","text":"native-text"}],
                        "structuredContent":{"k":"v"},
                        "resultType":"complete"}
                    }
                    """;
            assertThatJson(call.body()).isEqualTo(expected);
        }
    }

    @Test
    void mcpJavaNativeToolResponseErrorIsTranslatedAsIsErrorOverWire() throws Exception {
        try (var server = startServer(new RichResultFixture());
                var client = McpTestClients.latest(server.port())) {
            var call = client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"tools/call",
                     "params":{"name":"richError","arguments":{}}}
                    """);

            // language=json
            var expected = """
                    {"jsonrpc":"2.0","id":1,"result":{
                        "content":[{"type":"text","text":"boom"}],
                        "isError":true,
                        "resultType":"complete"}
                    }
                    """;
            assertThatJson(call.body()).isEqualTo(expected);
        }
    }

    record Point(int x, int y) {}

    @SuppressWarnings("unused")
    static class CompositeResultFixture {
        @Tool(name = "makePoint")
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

    interface Operation<T> {
        @Tool(name = "run", description = "Runs an operation")
        String run(@ToolArg(name = "input", description = "the input") T input);
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
