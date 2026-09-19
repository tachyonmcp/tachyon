/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2026_07_28;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;

import dev.tachyonmcp.api.server.domain.ToolAnnotations;
import dev.tachyonmcp.api.server.features.tasks.TaskSupport;
import dev.tachyonmcp.api.server.features.tools.ToolDescriptor;
import dev.tachyonmcp.e2e.mcp.AbstractToolCapabilitiesContractTest;
import dev.tachyonmcp.testkit.Mcp20260728Client;
import dev.tachyonmcp.testkit.McpTestClients;
import java.util.stream.Stream;
import net.javacrumbs.jsonunit.core.Option;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ToolCapabilitiesContractTest extends AbstractToolCapabilitiesContractTest<Mcp20260728Client> {

    @Override
    protected Mcp20260728Client createTestClient() {
        return createTestClient(port);
    }

    @Override
    protected Mcp20260728Client createTestClient(int port) {
        return McpTestClients.latest(port);
    }

    @Override
    protected Mcp20260728Client readyClient() {
        return createModernTestClient();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("taskSupportWithoutExecutionField")
    protected void shouldIncludeExecutionTaskSupport(String toolName, boolean hasExecution, ToolDescriptor descriptor)
            throws Exception {
        startServerWith(s -> s.tools().register(descriptor, OK));

        try (var client = readyClient()) {
            var response = listTools(client);

            assertThatJson(response.body()).inPath("$.result.tools[0].name").isEqualTo(toolName);
            // v2026-07-28 does not include execution field in tools/list
            assertThatJson(response.body())
                    .inPath("$.result.tools[0]")
                    .isObject()
                    .doesNotContainKey("execution");
        }
    }

    static Stream<Arguments> taskSupportWithoutExecutionField() {
        return Stream.of(
                Arguments.of("task-aware-tool", false, taskAwareToolDescriptor(TaskSupport.OPTIONAL)),
                Arguments.of("simple", false, simpleToolDescriptor("simple", "A simple tool")));
    }

    @Test
    protected void shouldRegisterWithFullDescriptor() throws Exception {
        var annotations = ToolAnnotations.of(null, true, false, null, null);
        startEmptyServer();
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
                {"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"full-tool","title":"Full Tool","description":"A tool with all metadata","inputSchema":{"type":"object","properties":{"message":{"type":"string","description":"Input"}},"required":["message"]},"outputSchema":{"type":"object","properties":{"result":{"type":"string","description":"The output result"}}},"annotations":{"readOnlyHint":true,"destructiveHint":false}}]}}
                """;
            assertThatJson(response.body()).when(Option.IGNORING_EXTRA_FIELDS).isEqualTo(expected.trim());
        }
    }
}
