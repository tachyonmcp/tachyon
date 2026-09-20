/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.annotations.langchain4j;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.testkit.McpTestClients;
import dev.tachyonmcp.testkit.McpTestServers;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Starts a real Tachyon server (Netty transport, port 0) with {@link LangChain4jAnnotationProvider}
 * wired through {@code ServerBuilder.annotations(...)} and exercises the annotated handlers over
 * the MCP JSON-RPC wire protocol. Spec ref: MCP 2025-11-25 tools.
 */
class LangChain4jAnnotationServerIntegrationTest {

    @SuppressWarnings("unused")
    static class Fixture {

        @Tool("Adds numbers")
        int add(@P(name = "a", description = "first operand") int a, @P(value = "second", required = false) Integer b) {
            return a + (b == null ? 0 : b);
        }
    }

    private static TachyonServer startServer() {
        return startServer(new Fixture());
    }

    private static TachyonServer startServer(Object fixture) {
        return McpTestServers.start(
                b -> b.annotations(annotations -> {
                    annotations.withProvider(LangChain4jAnnotationProvider.instance());
                    annotations.register(fixture);
                }),
                server -> {});
    }

    @Test
    void annotatedToolIsAdvertisedWithSchemaAndCallable() throws Exception {
        try (var server = startServer();
                var client = McpTestClients.latest(server.port())) {
            var list = client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"tools/list"}
                    """);

            // language=json
            var expectedList = """
                    {"jsonrpc":"2.0","id":1,"result":{
                        "tools":[{
                            "name":"add",
                            "description":"Adds numbers",
                            "inputSchema":{"type":"object",
                                "properties":{
                                    "a":{"type":"integer","description":"first operand"},
                                    "b":{"type":"integer","description":"second"}},
                                "required":["a"]}
                        }],
                        "resultType":"complete","ttlMs":0,"cacheScope":"public"}
                    }
                    """;
            assertThatJson(list.body()).isEqualTo(expectedList);

            var call = client.post("""
                    {"jsonrpc":"2.0","id":2,"method":"tools/call",
                     "params":{"name":"add","arguments":{"a":3,"b":4}}}
                    """);

            // language=json
            var expectedCall = """
                    {"jsonrpc":"2.0","id":2,"result":{
                        "content":[{"type":"text","text":"7"}],
                        "resultType":"complete"}
                    }
                    """;
            assertThatJson(call.body()).isEqualTo(expectedCall);
        }
    }

    @Test
    void optionalArgumentMayBeOmittedOnTheWire() throws Exception {
        try (var server = startServer();
                var client = McpTestClients.latest(server.port())) {
            var call = client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"tools/call",
                     "params":{"name":"add","arguments":{"a":5}}}
                    """);

            // language=json
            var expected = """
                    {"jsonrpc":"2.0","id":1,"result":{
                        "content":[{"type":"text","text":"5"}],
                        "resultType":"complete"}
                    }
                    """;
            assertThatJson(call.body()).isEqualTo(expected);
        }
    }

    @SuppressWarnings("unused")
    static class DefaultValueFixture {
        @Tool("Uses default")
        int withDefault(@P(defaultValue = "10") int limit) {
            return limit;
        }
    }

    @Test
    void missingArgumentWithDefaultValueUsesTheDeclaredDefaultOverWire() throws Exception {
        try (var server = startServer(new DefaultValueFixture());
                var client = McpTestClients.latest(server.port())) {
            var call = client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"tools/call",
                     "params":{"name":"withDefault","arguments":{}}}
                    """);

            // language=json
            var expected = """
                    {"jsonrpc":"2.0","id":1,"result":{
                        "content":[{"type":"text","text":"10"}],
                        "resultType":"complete"}
                    }
                    """;
            assertThatJson(call.body()).isEqualTo(expected);
        }
    }

    @SuppressWarnings("unused")
    static class OptionalArgFixture {
        @Tool("Optional filter")
        String withOptionalFilter(Optional<String> filter) {
            return filter == null ? "no-filter" : filter.map(f -> "filter=" + f).orElse("no-filter");
        }
    }

    @Test
    void presentOptionalArgumentIsWrappedRatherThanPassedRawOverWire() throws Exception {
        try (var server = startServer(new OptionalArgFixture());
                var client = McpTestClients.latest(server.port())) {
            var call = client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"tools/call",
                     "params":{"name":"withOptionalFilter","arguments":{"filter":"abc"}}}
                    """);

            // language=json
            var expected = """
                    {"jsonrpc":"2.0","id":1,"result":{
                        "content":[{"type":"text","text":"filter=abc"}],
                        "resultType":"complete"}
                    }
                    """;
            assertThatJson(call.body()).isEqualTo(expected);
        }
    }

    @Test
    void missingOptionalArgumentStillPassesNullOverWire() throws Exception {
        try (var server = startServer(new OptionalArgFixture());
                var client = McpTestClients.latest(server.port())) {
            var call = client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"tools/call",
                     "params":{"name":"withOptionalFilter","arguments":{}}}
                    """);

            // language=json
            var expected = """
                    {"jsonrpc":"2.0","id":1,"result":{
                        "content":[{"type":"text","text":"no-filter"}],
                        "resultType":"complete"}
                    }
                    """;
            assertThatJson(call.body()).isEqualTo(expected);
        }
    }

    enum Priority {
        LOW,
        HIGH
    }

    record SearchRequest(String query, int limit) {}

    @SuppressWarnings("unused")
    static class RichTypeFixture {
        @Tool("Search with rich types")
        String search(SearchRequest request, Priority priority, java.util.List<String> tags) {
            return request.query() + "/" + request.limit() + "/" + priority + "/" + String.join(",", tags);
        }
    }

    @Test
    void recordEnumAndListParametersAreDescribedAndBoundCorrectlyOverWire() throws Exception {
        try (var server = startServer(new RichTypeFixture());
                var client = McpTestClients.latest(server.port())) {
            var list = client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"tools/list"}
                    """);

            // language=json
            var expectedList = """
                    {"jsonrpc":"2.0","id":1,"result":{
                        "tools":[{
                            "name":"search",
                            "description":"Search with rich types",
                            "inputSchema":{"type":"object",
                                "properties":{
                                    "request":{"type":"object",
                                        "properties":{"query":{"type":"string"},"limit":{"type":"integer"}},
                                        "required":["query","limit"]},
                                    "priority":{"type":"string","enum":["LOW","HIGH"]},
                                    "tags":{"type":"array","items":{"type":"string"}}},
                                "required":["request","priority","tags"]}
                        }],
                        "resultType":"complete","ttlMs":0,"cacheScope":"public"}
                    }
                    """;
            assertThatJson(list.body()).isEqualTo(expectedList);

            var call = client.post("""
                    {"jsonrpc":"2.0","id":2,"method":"tools/call",
                     "params":{"name":"search","arguments":{
                         "request":{"query":"dragons","limit":5},
                         "priority":"HIGH",
                         "tags":["a","b"]}}}
                    """);

            // language=json
            var expectedCall = """
                    {"jsonrpc":"2.0","id":2,"result":{
                        "content":[{"type":"text","text":"dragons/5/HIGH/a,b"}],
                        "resultType":"complete"}
                    }
                    """;
            assertThatJson(call.body()).isEqualTo(expectedCall);
        }
    }

    interface Operation<T> {
        @Tool("Runs an operation")
        String run(@P(name = "input", description = "the input") T input);
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
                                "required":["input"]}
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

    record Payload(String value) {}

    static class PayloadOperation implements Operation<Payload> {
        @Override
        public String run(Payload input) {
            return input.value();
        }
    }

    /** LangChain4j cannot see the bound type, so a type the scalar mapping cannot describe is rejected, not advertised wrongly. */
    @Test
    void aBoundTypeTheScalarMappingCannotDescribeFailsRegistration() {
        assertThatThrownBy(() -> startServer(new PayloadOperation()))
                .hasStackTraceContaining("Unsupported parameter type")
                .hasStackTraceContaining("Payload");
    }
}
