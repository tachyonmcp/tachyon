/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2025_11_25;

import static dev.tachyonmcp.testkit.McpHttpResponseAssert.assertThatResponse;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;

import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.api.server.features.tasks.TaskSupport;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.extensions.tasks.TasksExtension;
import dev.tachyonmcp.testkit.TestTaskConnector;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TaskAugmentedToolTest extends AbstractStatefulMcpE2eTest {

    private TestTaskConnector taskEngine;
    private TaskSnapshot snapshot;

    @Override
    protected void startDefaultServer() {
        snapshot = TaskSnapshot.working("external-42", Instant.parse("2026-08-27T07:00:00Z"), 1);
        taskEngine = new TestTaskConnector().publish(snapshot);
        startServer(
                builder -> builder.withExtension(TasksExtension.class, t -> t.connector(taskEngine.connector())),
                registrar -> {
                    registrar
                            .tools()
                            .register(
                                    b -> b.name("required").taskSupport(TaskSupport.REQUIRED),
                                    (context, request) -> ToolResult.task(snapshot));
                    registrar
                            .tools()
                            .register(
                                    b -> b.name("optional").taskSupport(TaskSupport.OPTIONAL),
                                    (context, request) -> ToolResult.text("inline"));
                    registrar
                            .tools()
                            .register(
                                    b -> b.name("forbidden").taskSupport(TaskSupport.FORBIDDEN),
                                    (context, request) -> ToolResult.text("inline"));
                    registrar.tools().register(b -> b.name("plain"), (context, request) -> ToolResult.text("inline"));
                });
    }

    @Test
    void requiredToolRejectsInlineCall() throws Exception {
        try (var client = createTestClient()) {
            client.initialize();
            var response = client.sendRpc("""
                    {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                      "name":"required","arguments":{}}}
                    """);
            // 2025-11-25 Tasks § Tool-Level Negotiation 2.3: "required" called inline MUST be -32601
            assertThatResponse(response)
                    .isJsonRpcError()
                    .hasId(2)
                    .hasErrorCode(-32601)
                    .hasErrorMessage("Task augmentation required for this tool");
        }
    }

    @Test
    void optionalToolMayReturnInlineResult() throws Exception {
        try (var client = createTestClient()) {
            client.initialize();
            var response = client.sendRpc("""
                    {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                      "name":"optional","arguments":{}}}
                    """);
            assertThatJson(response.body()).inPath("$.result.content[0].text").isEqualTo("inline");
        }
    }

    @Test
    void optionalToolRejectsInlineResultForTaskAugmentedCall() throws Exception {
        try (var client = createTestClient()) {
            client.initialize();
            var response = client.sendRpc("""
                    {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                      "name":"optional","arguments":{},"task":{}}}
                    """);
            assertThatResponse(response).isJsonRpcError().hasErrorCode(-32603);
        }
    }

    @Test
    void forbiddenToolRejectsTaskAugmentation() throws Exception {
        try (var client = createTestClient()) {
            client.initialize();
            var response = client.sendRpc("""
                    {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                      "name":"forbidden","arguments":{},"task":{}}}
                    """);
            // 2025-11-25 Tasks § Tool-Level Negotiation 2.1: "forbidden" as a task SHOULD be -32601
            assertThatResponse(response)
                    .isJsonRpcError()
                    .hasId(2)
                    .hasErrorCode(-32601)
                    .hasErrorMessage("Task augmentation not supported for this tool");
        }
    }

    @Test
    void toolWithoutTaskSupportRejectsTaskAugmentedCallLikeForbidden() throws Exception {
        try (var client = createTestClient()) {
            client.initialize();
            var response = client.sendRpc("""
                    {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                      "name":"plain","arguments":{},"task":{}}}
                    """);
            // 2025-11-25 Tasks § Tool-Level Negotiation 2.1: taskSupport absent is treated as "forbidden"
            assertThatResponse(response)
                    .isJsonRpcError()
                    .hasId(2)
                    .hasErrorCode(-32601)
                    .hasErrorMessage("Task augmentation not supported for this tool");
        }
    }
}
