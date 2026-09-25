/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2025_11_25;

import static dev.tachyonmcp.testkit.McpHttpResponseAssert.assertThatResponse;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.server.domain.TaskResult;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.api.server.features.tasks.TaskState;
import dev.tachyonmcp.api.server.features.tasks.TaskSupport;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.config.TasksConfig;
import dev.tachyonmcp.testkit.TestTaskConnector;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TasksCoreTest extends AbstractStatefulMcpE2eTest {

    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(5);

    private TestTaskConnector taskConnector;

    @Override
    protected void startDefaultServer() {
        taskConnector = new TestTaskConnector();
        startServer(
                it -> it.capabilities(c -> c.tasks(TasksConfig.builder()
                        .enabled(true)
                        .connector(taskConnector.connector())
                        .pollInterval(DEFAULT_POLL_INTERVAL)
                        .build())),
                registrar -> registrar
                        .tools()
                        .register(
                                b -> b.name("start-workflow").taskSupport(TaskSupport.REQUIRED), (context, request) -> {
                                    var initial = working("workflow-started-by-tool", 1);
                                    taskConnector.start(initial);
                                    return ToolResult.task(initial);
                                }));
    }

    @Test
    void toolStartsTaskBeforeReturningItsHandle() throws Exception {
        taskConnector.reset();

        try (var client = createTestClient()) {
            client.initialize();
            var callJson = client.sendRpc("""
                    {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                      "name":"start-workflow","arguments":{},"task":{}}}
                    """);

            assertThatJson(callJson.body()).inPath("$.result.task.taskId").isEqualTo("workflow-started-by-tool");
            assertThatJson(callJson.body()).inPath("$.result.task.status").isEqualTo("working");
            assertThat(taskConnector.startedTaskIds()).containsExactly("workflow-started-by-tool");

            var getJson = client.sendRpc("""
                    {"jsonrpc":"2.0","id":3,"method":"tasks/get","params":{
                      "taskId":"workflow-started-by-tool"}}
                    """);
            assertThatJson(getJson.body()).inPath("$.result.taskId").isEqualTo("workflow-started-by-tool");
            assertThatJson(getJson.body()).inPath("$.result.status").isEqualTo("working");
        }
    }

    @Test
    void listsAndGetsConnectorSnapshots() throws Exception {
        taskConnector.reset();
        var first = working("workflow-1", 1);
        var second = working("workflow-2", 1);
        taskConnector.publish(first).publish(second);

        try (var client = createTestClient()) {
            client.initialize();
            var listJson = client.sendRpc("""
                    {"jsonrpc":"2.0","id":2,"method":"tasks/list","params":{}}
                    """);
            assertThatJson(listJson.body()).inPath("$.result.tasks.length()").isEqualTo(2);
            assertThatJson(listJson.body())
                    .inPath("$.result.tasks[0].pollInterval")
                    .isEqualTo(DEFAULT_POLL_INTERVAL.toMillis());
            assertThatJson(listJson.body())
                    .inPath("$.result.tasks[1].pollInterval")
                    .isEqualTo(DEFAULT_POLL_INTERVAL.toMillis());
            assertThat(taskConnector.listRequests()).singleElement().satisfies(request -> {
                assertThat(request.limit()).isPositive();
                assertThat(request.cursor()).isNull();
            });
            assertThat(server.tasks().get("workflow-1"))
                    .as("tasks/list is a read: it never caches connector snapshots")
                    .isNull();
            assertThat(server.tasks().get("workflow-2")).isNull();
            assertThat(client.notifications())
                    .as("tasks/list never emits task status")
                    .noneMatch(n -> n.method().equals("notifications/tasks/status"));

            var getJson = client.sendRpc("""
                    {"jsonrpc":"2.0","id":3,"method":"tasks/get","params":{"taskId":"workflow-1"}}
                    """);
            assertThatJson(getJson.body()).inPath("$.result.taskId").isEqualTo("workflow-1");
            assertThatJson(getJson.body()).inPath("$.result.status").isEqualTo("working");
            assertThat(taskConnector.refreshedTaskIds()).contains("workflow-1");
        }
    }

    @Test
    void getOnUnknownTaskReturnsTheSameErrorShapeAsCancelAndUpdate() throws Exception {
        taskConnector.reset();

        try (var client = createTestClient()) {
            client.initialize();
            var getJson = client.sendRpc("""
                    {"jsonrpc":"2.0","id":2,"method":"tasks/get","params":{"taskId":"unknown-workflow"}}
                    """);
            assertThatResponse(getJson).isJsonRpcError().hasErrorMessageContaining("Task not found");

            var cancelJson = client.sendRpc("""
                    {"jsonrpc":"2.0","id":3,"method":"tasks/cancel","params":{"taskId":"unknown-workflow"}}
                    """);
            assertThatResponse(cancelJson).isJsonRpcError().hasErrorMessageContaining("Task not found");
        }
    }

    @Test
    void cancelsThroughConnector() throws Exception {
        taskConnector.reset();
        taskConnector.publish(working("workflow-cancel", 1));

        try (var client = createTestClient()) {
            client.initialize();
            var cancelJson = client.sendRpc("""
                    {"jsonrpc":"2.0","id":2,"method":"tasks/cancel","params":{"taskId":"workflow-cancel"}}
                    """);
            assertThatJson(cancelJson.body()).inPath("$.result.taskId").isEqualTo("workflow-cancel");
            assertThatJson(cancelJson.body()).inPath("$.result.status").isEqualTo("cancelled");
            assertThat(taskConnector.cancelledTaskIds()).containsExactly("workflow-cancel");
        }
    }

    @Test
    void returnsCompletedConnectorResult() throws Exception {
        taskConnector.reset();
        var completed = TaskSnapshot.builder()
                .from(working("workflow-complete", 1))
                .status(TaskState.COMPLETED)
                .result(TaskResult.completed(Map.of("output", "success")))
                .revision(2)
                .build();
        taskConnector.publish(completed);

        try (var client = createTestClient()) {
            client.initialize();
            var resultJson = client.sendRpc("""
                    {"jsonrpc":"2.0","id":3,"method":"tasks/result","params":{"taskId":"workflow-complete"}}
                    """);
            assertThatResponse(resultJson).isSuccess().hasStructuredContent("""
                    {"output":"success"}
                    """);
            assertThat(taskConnector.awaitedTaskIds()).containsExactly("workflow-complete");
        }
    }

    @Test
    void unknownTaskReturnsError() throws Exception {
        taskConnector.reset();
        try (var client = createTestClient()) {
            client.initialize();
            var getJson = client.sendRpc("""
                    {"jsonrpc":"2.0","id":2,"method":"tasks/get","params":{"taskId":"missing"}}
                    """);
            assertThatResponse(getJson).isJsonRpcError().hasErrorCode(-32602);
        }
    }

    @Test
    void cancelUnknownTaskReturnsError() throws Exception {
        taskConnector.reset();
        try (var client = createTestClient()) {
            client.initialize();
            var cancelJson = client.sendRpc("""
                    {"jsonrpc":"2.0","id":2,"method":"tasks/cancel","params":{"taskId":"missing"}}
                    """);
            assertThatResponse(cancelJson).isJsonRpcError().hasErrorCode(-32602);
        }
    }

    @Test
    void cancelAlreadyTerminalTaskReturnsCurrentSnapshot() throws Exception {
        taskConnector.reset();
        var completed = TaskSnapshot.builder()
                .from(working("workflow-terminal", 1))
                .status(TaskState.COMPLETED)
                .result(TaskResult.completed(Map.of("output", "done")))
                .revision(2)
                .build();
        taskConnector.publish(completed);

        try (var client = createTestClient()) {
            client.initialize();
            client.sendRpc("""
                    {"jsonrpc":"2.0","id":2,"method":"tasks/get","params":{"taskId":"workflow-terminal"}}
                    """);

            var cancelJson = client.sendRpc("""
                    {"jsonrpc":"2.0","id":3,"method":"tasks/cancel","params":{"taskId":"workflow-terminal"}}
                    """);
            assertThatJson(cancelJson.body()).inPath("$.result.taskId").isEqualTo("workflow-terminal");
            assertThatJson(cancelJson.body()).inPath("$.result.status").isEqualTo("completed");
            assertThat(taskConnector.cancelledTaskIds()).containsExactly("workflow-terminal");
        }
    }

    @Test
    void updateNotAvailableForLegacyClient() throws Exception {
        taskConnector.reset();
        taskConnector.publish(working("workflow-legacy-update", 1));

        try (var client = createTestClient()) {
            client.initialize();
            var updateJson = client.sendRpc("""
                    {"jsonrpc":"2.0","id":2,"method":"tasks/update","params":{
                      "taskId":"workflow-legacy-update","inputResponses":{}}}
                    """);
            assertThatResponse(updateJson).isJsonRpcError().hasErrorCode(-32601);
        }
    }

    private static TaskSnapshot working(String taskId, long revision) {
        return TaskSnapshot.working(taskId, Instant.parse("2026-08-27T07:00:00Z"), revision);
    }
}
