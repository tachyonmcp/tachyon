/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp;

import static dev.tachyonmcp.testkit.McpHttpResponseAssert.assertThatResponse;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.json.JsonSchema;
import dev.tachyonmcp.api.server.domain.ToolAnnotations;
import dev.tachyonmcp.api.server.features.tasks.TaskSupport;
import dev.tachyonmcp.api.server.features.tools.ToolDescriptor;
import dev.tachyonmcp.api.server.features.tools.ToolFn;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.ServerBuilder;
import dev.tachyonmcp.extensions.tasks.TasksExtension;
import dev.tachyonmcp.testkit.McpClient;
import dev.tachyonmcp.testkit.TestTaskConnector;
import java.net.http.HttpResponse;
import java.util.stream.Stream;
import net.javacrumbs.jsonunit.core.Option;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Tool descriptor metadata, structured content, and registration scenarios that hold under both MCP
 * protocol revisions: only the client creation and the {@code tools/list} response envelope differ
 * (2026-07-28 adds {@code resultType}/{@code ttlMs}/{@code cacheScope}), supplied by subclasses in
 * {@code v2025_11_25}/{@code v2026_07_28}. Assertions use {@link Option#IGNORING_EXTRA_FIELDS} where
 * the envelope shape diverges.
 */
public abstract class AbstractToolCapabilitiesContractTest<C extends McpClient> extends AbstractStatelessMcpE2eTest<C> {

    /** Returns a client ready to send requests (handshake already performed, if the version needs one). */
    protected abstract C readyClient() throws Exception;

    // region: Output Schema Tests

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', textBlock = """
            output-schema-tool | true  | object
            simple             | false |
            """)
    void shouldIncludeOutputSchema(String toolName, boolean hasSchema, String schemaType) throws Exception {
        ToolDescriptor descriptor;
        if (hasSchema) {
            descriptor = outputSchemaToolDescriptor(OUTPUT_SCHEMA);
        } else {
            descriptor = simpleToolDescriptor(toolName, "A " + toolName + " tool");
        }
        startServerWith(s -> s.tools().register(descriptor, OK));

        try (var client = readyClient()) {
            var response = listTools(client);

            final String expected;
            if (hasSchema) {
                // language=JSON
                expected = """
                    {
                      "jsonrpc": "2.0",
                      "id": 2,
                      "result": {
                        "tools": [
                          {
                            "name": "output-schema-tool",
                            "description": "A tool with output schema",
                            "inputSchema": {
                              "type": "object",
                              "properties": {
                                "message": { "type": "string", "description": "Input" }
                              },
                              "required": ["message"]
                            },
                            "outputSchema": {
                              "type": "object",
                              "properties": {
                                "result": { "type": "string", "description": "The output result" }
                              }
                            }
                          }
                        ]
                      }
                    }
                    """;
            } else {
                // language=JSON
                expected = """
                    {
                      "jsonrpc": "2.0",
                      "id": 2,
                      "result": {
                        "tools": [
                          {
                            "name": "%s",
                            "description": "A %s tool",
                            "inputSchema": {
                              "type": "object",
                              "properties": {
                                "message": { "type": "string", "description": "Input" }
                              },
                              "required": ["message"]
                            }
                          }
                        ]
                      }
                    }
                    """.formatted(toolName, toolName);
            }
            assertThatJson(response.body()).when(Option.IGNORING_EXTRA_FIELDS).isEqualTo(expected);
        }
    }

    @Test
    void shouldIncludeMultipleToolsWithMixedOutputSchemas() throws Exception {
        startServerWith(s -> s.tools()
                .register(simpleToolDescriptor("tool-a", "Tool A"), OK)
                .register(outputSchemaToolDescriptor(OUTPUT_SCHEMA), OK)
                .register(simpleToolDescriptor("tool-b", "Tool B"), OK));

        try (var client = readyClient()) {
            var response = listTools(client);

            var mapper = new ObjectMapper();
            var root = mapper.readTree(response.body());
            var tools = root.at("/result/tools");
            assertThat(tools).isNotNull();
            assertThat(tools.size()).isEqualTo(3);

            for (var tool : tools) {
                var name = tool.get("name").asString();
                var hasOutputSchema = tool.has("outputSchema");
                if ("output-schema-tool".equals(name)) {
                    assertThat(hasOutputSchema).isTrue();
                    assertThat(tool.get("outputSchema").get("type").asString()).isEqualTo("object");
                } else {
                    assertThat(hasOutputSchema).isFalse();
                }
            }
        }
    }

    // endregion

    // region: Execution / Task Support Tests

    /** Tools declaring task support need {@link TasksExtension}. */
    protected static void withTasks(ServerBuilder builder) {
        builder.withExtension(TasksExtension.class, t -> t.connector(new TestTaskConnector().connector()));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource
    protected void shouldIncludeExecutionTaskSupport(String toolName, boolean hasExecution, ToolDescriptor descriptor)
            throws Exception {
        startServer(
                AbstractToolCapabilitiesContractTest::withTasks, s -> s.tools().register(descriptor, OK));

        try (var client = readyClient()) {
            var response = listTools(client);

            assertThatJson(response.body()).inPath("$.result.tools[0].name").isEqualTo(toolName);
            if (hasExecution) {
                assertThatJson(response.body())
                        .inPath("$.result.tools[0].execution.taskSupport")
                        .isEqualTo("optional");
            } else {
                assertThatJson(response.body())
                        .inPath("$.result.tools[0]")
                        .isObject()
                        .doesNotContainKey("execution");
            }
        }
    }

    static Stream<Arguments> shouldIncludeExecutionTaskSupport() {
        return Stream.of(
                Arguments.of("task-aware-tool", true, taskAwareToolDescriptor(TaskSupport.OPTIONAL)),
                Arguments.of("simple", false, simpleToolDescriptor("simple", "A simple tool")));
    }

    // endregion

    // region: Descriptor Registration Tests

    @Test
    void shouldRegisterWithMinimalDescriptor() throws Exception {
        startEmptyServer();
        server.tools().register(builder -> builder.name("minimal-tool"), OK);

        try (var client = readyClient()) {
            var response = listTools(client);

            // language=JSON
            var expected = """
                {"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"minimal-tool", "inputSchema":{"type":"object"}}]}}
                """;
            assertThatJson(response.body()).when(Option.IGNORING_EXTRA_FIELDS).isEqualTo(expected.trim());
        }
    }

    // region: Structured content in tools/call

    @Test
    void shouldReturnStructuredContentAndTextFallback() throws Exception {
        startServerWith(s -> s.tools()
                .register(
                        b -> b.name("structured")
                                .description("Returns structured content")
                                .inputSchema(INPUT_SCHEMA),
                        (ctx, request) -> {
                            var msg = request.arguments().stringValue("message");
                            var echo = JsonNodeFactory.instance.objectNode().put("echo", msg);
                            return ToolResult.structured(echo, "Echo: " + msg);
                        }));

        try (var client = readyClient()) {
            var response = client.post("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"structured","arguments":{"message":"hi"}}}
                """);

            assertThatResponse(response)
                    .hasStatus(200)
                    .isSuccess()
                    .hasTextContent("Echo: hi")
                    .hasStructuredContent("{\"echo\":\"hi\"}");
        }
    }

    // endregion

    @Test
    protected void shouldRegisterWithFullDescriptor() throws Exception {
        var annotations = ToolAnnotations.of(null, true, false, null, null);
        startServer(AbstractToolCapabilitiesContractTest::withTasks, s -> {});
        server.tools()
                .register(
                        b -> b.name("full-tool")
                                .title("Full Tool")
                                .description("A tool with all metadata")
                                .inputSchema(INPUT_SCHEMA)
                                .outputSchema(OUTPUT_SCHEMA)
                                .taskSupport(TaskSupport.OPTIONAL)
                                .annotations(annotations),
                        OK);

        try (var client = readyClient()) {
            var response = listTools(client);

            var expected = """
                {"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"full-tool","title":"Full Tool","description":"A tool with all metadata","inputSchema":{"type":"object","properties":{"message":{"type":"string","description":"Input"}},"required":["message"]},"outputSchema":{"type":"object","properties":{"result":{"type":"string","description":"The output result"}}},"execution":{"taskSupport":"optional"},"annotations":{"readOnlyHint":true,"destructiveHint":false}}]}}
                """;
            assertThatJson(response.body()).when(Option.IGNORING_EXTRA_FIELDS).isEqualTo(expected.trim());
        }
    }

    // endregion

    // region: Tool Handler Implementations

    protected static HttpResponse<String> listTools(McpClient client) throws Exception {
        return client.post("""
                {"jsonrpc":"2.0","id":2,"method":"tools/list"}
                """);
    }

    public static ToolDescriptor outputSchemaToolDescriptor(JsonSchema outputSchema) {
        return ToolDescriptor.builder()
                .name("output-schema-tool")
                .description("A tool with output schema")
                .inputSchema(INPUT_SCHEMA)
                .outputSchema(outputSchema)
                .build();
    }

    public static ToolDescriptor simpleToolDescriptor(String name, String description) {
        return ToolDescriptor.builder()
                .name(name)
                .description(description)
                .inputSchema(INPUT_SCHEMA)
                .build();
    }

    public static ToolDescriptor taskAwareToolDescriptor(TaskSupport taskSupport) {
        return ToolDescriptor.builder()
                .name("task-aware-tool")
                .description("A task-aware tool")
                .inputSchema(INPUT_SCHEMA)
                .taskSupport(taskSupport)
                .build();
    }

    // ---- JSON schemas ----

    public static final JsonSchema OUTPUT_SCHEMA = JsonSchema.from(buildOutputSchema(), JsonNode.class);

    private static JsonNode buildOutputSchema() {
        var schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        var result = props.putObject("result");
        result.put("type", "string");
        result.put("description", "The output result");
        return schema;
    }

    public static final JsonSchema INPUT_SCHEMA = JsonSchema.from(buildInputSchema(), JsonNode.class);

    public static final ToolFn OK = (ctx, request) -> ToolResult.text("ok");

    private static JsonNode buildInputSchema() {
        var schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        var msg = props.putObject("message");
        msg.put("type", "string");
        msg.put("description", "Input");
        var req = schema.putArray("required");
        req.add("message");
        return schema;
    }
}
