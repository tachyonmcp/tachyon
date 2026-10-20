/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp;

import static dev.tachyonmcp.testkit.JsonRpcResponseAssert.assertThat;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;

import dev.tachyonmcp.api.json.JsonSchema;
import dev.tachyonmcp.api.server.features.tools.ToolDescriptor;
import dev.tachyonmcp.testkit.McpTestClients;
import dev.tachyonmcp.testkit.McpTestServers;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Stream;
import kotlinx.serialization.SerialName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Verifies {@code Tools.register(Class, Class, ...)} / {@code registerAsync(Class, Class, ...)} end to end. */
class TypedToolRegistrationTest {

    @SerialName("FarewellRequest")
    record FarewellRequest(String name) {}

    @SerialName("FarewellResponse")
    record FarewellResponse(String farewell) {}

    private static final JsonSchema GREET_REQUEST_SCHEMA = JsonSchema.unchecked("""
            {"type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]}
            """);
    private static final JsonSchema GREET_RESPONSE_SCHEMA = JsonSchema.unchecked("""
            {"type": "object", "properties": {"greeting": {"type": "string"}}, "required": ["greeting"]}
            """);
    private static final JsonSchema FAREWELL_REQUEST_SCHEMA = JsonSchema.unchecked("""
            {"type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]}
            """);
    private static final JsonSchema FAREWELL_RESPONSE_SCHEMA = JsonSchema.unchecked("""
            {"type": "object", "properties": {"farewell": {"type": "string"}}, "required": ["farewell"]}
            """);

    record GreetRequest(String name) {}

    record GreetResponse(String greeting) {}

    @Test
    void syncRegisterDecodesArgumentsAndWrapsTypedResult() throws Exception {
        try (var server = McpTestServers.start(
                        b -> {},
                        s -> s.tools()
                                .register(
                                        GreetRequest.class,
                                        GreetResponse.class,
                                        d -> d.name("greet")
                                                .description("Greets by name")
                                                .inputSchema(GREET_REQUEST_SCHEMA)
                                                .outputSchema(GREET_RESPONSE_SCHEMA),
                                        (ctx, input) -> new GreetResponse("Hello, " + input.name() + "!")));
                var client = McpTestClients.latest(server.port())) {
            var response = client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"greet","arguments":{"name":"Ada"}}}
                    """);

            assertThat(response).isSuccess().hasId(1).hasResult("""
                            {"content":[{"type":"text","text":"{\\"greeting\\":\\"Hello, Ada!\\"}"}],
                             "structuredContent":{"greeting":"Hello, Ada!"},
                             "resultType":"complete"}
                            """);
        }
    }

    @Test
    void syncRegisterAdvertisesTheExplicitSchemas() throws Exception {
        try (var server = McpTestServers.start(
                        b -> {},
                        s -> s.tools()
                                .register(
                                        GreetRequest.class,
                                        GreetResponse.class,
                                        d -> d.name("greet")
                                                .description("Greets by name")
                                                .inputSchema(GREET_REQUEST_SCHEMA)
                                                .outputSchema(GREET_RESPONSE_SCHEMA),
                                        (ctx, input) -> new GreetResponse("Hello, " + input.name() + "!")));
                var client = McpTestClients.latest(server.port())) {
            var response = client.post("""
                    {"jsonrpc":"2.0","id":2,"method":"tools/list"}
                    """);

            // language=JSON
            var expected = """
                    {"jsonrpc":"2.0","id":2,"result":{
                        "cacheScope":"public",
                        "resultType":"complete",
                        "ttlMs":0,
                        "tools":[{
                            "name":"greet",
                            "description":"Greets by name",
                            "inputSchema":{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]},
                            "outputSchema":{"type":"object","properties":{"greeting":{"type":"string"}},"required":["greeting"]}
                        }]
                    }}
                    """;
            assertThatJson(response.body()).isEqualTo(expected);
        }
    }

    @Test
    void asyncRegisterWithConfigurerDecodesArgumentsAndWrapsTypedResult() throws Exception {
        try (var server = McpTestServers.start(
                        b -> {},
                        s -> s.tools()
                                .registerAsync(
                                        GreetRequest.class,
                                        GreetResponse.class,
                                        d -> d.name("greet-async")
                                                .inputSchema(GREET_REQUEST_SCHEMA)
                                                .outputSchema(GREET_RESPONSE_SCHEMA),
                                        (ctx, input) -> CompletableFuture.completedFuture(
                                                new GreetResponse("Hi, " + input.name() + "!"))));
                var client = McpTestClients.latest(server.port())) {
            var response = client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"tools/call",
                     "params":{"name":"greet-async","arguments":{"name":"Ada"}}}
                    """);

            assertThat(response).isSuccess().hasId(1).hasResult("""
                            {"content":[{"type":"text","text":"{\\"greeting\\":\\"Hi, Ada!\\"}"}],
                             "structuredContent":{"greeting":"Hi, Ada!"},
                             "resultType":"complete"}
                            """);
        }
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource
    void generatesTheOmittedSchemaWhileKeepingTheExplicitOne(
            String toolName, Consumer<ToolDescriptor.Builder> configurer, String expectedJson) throws Exception {
        try (var server = McpTestServers.start(
                        b -> {},
                        s -> s.tools()
                                .register(
                                        FarewellRequest.class,
                                        FarewellResponse.class,
                                        configurer,
                                        (ctx, input) -> new FarewellResponse("Bye, " + input.name() + "!")));
                var client = McpTestClients.latest(server.port())) {
            var response = client.post("""
                    {"jsonrpc":"2.0","id":2,"method":"tools/list"}
                    """);

            assertThatJson(response.body()).isEqualTo(expectedJson);
        }
    }

    static Stream<Arguments> generatesTheOmittedSchemaWhileKeepingTheExplicitOne() {
        return Stream.of(
                Arguments.of(
                        "farewell-output-generated",
                        (Consumer<ToolDescriptor.Builder>)
                                d -> d.name("farewell-output-generated").inputSchema(FAREWELL_REQUEST_SCHEMA),
                        // language=JSON
                        """
                        {"jsonrpc":"2.0","id":2,"result":{
                            "cacheScope":"public",
                            "resultType":"complete",
                            "ttlMs":0,
                            "tools":[{
                                "name":"farewell-output-generated",
                                "inputSchema":{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]},
                                "outputSchema":{
                                    "$schema":"https://json-schema.org/draft/2020-12/schema",
                                    "$id":"FarewellResponse",
                                    "type":"object",
                                    "properties":{"farewell":{"type":"string"}},
                                    "required":["farewell"]
                                }
                            }]
                        }}
                        """),
                Arguments.of(
                        "farewell-input-generated",
                        (Consumer<ToolDescriptor.Builder>)
                                d -> d.name("farewell-input-generated").outputSchema(FAREWELL_RESPONSE_SCHEMA),
                        // language=JSON
                        """
                        {"jsonrpc":"2.0","id":2,"result":{
                            "cacheScope":"public",
                            "resultType":"complete",
                            "ttlMs":0,
                            "tools":[{
                                "name":"farewell-input-generated",
                                "inputSchema":{
                                    "$schema":"https://json-schema.org/draft/2020-12/schema",
                                    "$id":"FarewellRequest",
                                    "type":"object",
                                    "properties":{"name":{"type":"string"}},
                                    "required":["name"]
                                },
                                "outputSchema":{"type":"object","properties":{"farewell":{"type":"string"}},"required":["farewell"]}
                            }]
                        }}
                        """));
    }

    @Test
    void asyncRegisterWithConfigurerGeneratesBothSchemasWhenDescriptorOmitsThem() throws Exception {
        try (var server = McpTestServers.start(
                        b -> {},
                        s -> s.tools()
                                .registerAsync(
                                        FarewellRequest.class,
                                        FarewellResponse.class,
                                        d -> d.name("farewell-async-generated"),
                                        (ctx, input) -> CompletableFuture.completedFuture(
                                                new FarewellResponse("Bye, " + input.name() + "!"))));
                var client = McpTestClients.latest(server.port())) {
            var response = client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"tools/call",
                     "params":{"name":"farewell-async-generated","arguments":{"name":"Ada"}}}
                    """);

            assertThat(response).isSuccess().hasId(1).hasResult("""
                            {"content":[{"type":"text","text":"{\\"farewell\\":\\"Bye, Ada!\\"}"}],
                             "structuredContent":{"farewell":"Bye, Ada!"},
                             "resultType":"complete"}
                            """);
        }
    }
}
