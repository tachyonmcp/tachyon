/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.tasks;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import dev.tachyonmcp.api.server.domain.TaskResult;
import dev.tachyonmcp.api.server.features.tasks.TaskConnector;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.api.server.features.tasks.TaskState;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.testkit.Mcp20251125Client;
import dev.tachyonmcp.testkit.Mcp20260728Client;
import dev.tachyonmcp.testkit.McpTestServers;
import dev.tachyonmcp.testkit.TestTaskConnector;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

/** The {@code tasks/*} methods the extension serves, per protocol version, over the wire. */
class TaskMethodsE2eTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-27T07:00:00Z");
    private static final String NOT_FOUND = "Task not found";

    @Test
    void legacyProtocolServesCancelAndBlockingResultButNotUpdate() throws Exception {
        var connector = new TestTaskConnector().publish(working("task-1")).publish(completed("done"));
        try (var server = startServer(connector.connector());
                var client = new Mcp20251125Client(server.port())) {
            client.initialize();

            var cancelled = client.post(rpc("tasks/cancel", "task-1"));
            assertThatJson(cancelled.body()).inPath("$.result.taskId").isEqualTo("task-1");
            assertThatJson(cancelled.body()).inPath("$.result.status").isEqualTo("cancelled");
            assertThat(connector.cancelledTaskIds()).containsExactly("task-1");

            var result = client.post(rpc("tasks/result", "done"));
            assertThatJson(result.body()).inPath("$.result.structuredContent").isEqualTo("{\"output\":\"success\"}");
            assertThat(connector.awaitedTaskIds()).containsExactly("done");

            assertError(
                    client.post(rpc("tasks/get", "missing")).body(), -32602, "Failed to retrieve task: " + NOT_FOUND);
            assertError(
                    client.post(rpc("tasks/cancel", "missing")).body(), -32602, "Failed to cancel task: " + NOT_FOUND);
            assertError(
                    client.post(rpc("tasks/result", "missing")).body(),
                    -32602,
                    "Failed to retrieve task: " + NOT_FOUND);
            assertThatJson(client.post(rpc("tasks/update", "task-1")).body())
                    .as("tasks/update is modern only")
                    .inPath("$.error.code")
                    .isEqualTo(-32601);
        }
    }

    @Test
    void modernProtocolCancelsWithAnEmptyResultAndAcceptsInputButHasNoLegacyMethods() throws Exception {
        var connector = new TestTaskConnector().publish(working("task-1"));
        try (var server = startServer(connector.connector());
                var client = new Mcp20260728Client(server.port())
                        .withExtensions(Map.of(TasksExtension.ID, JsonNodeFactory.instance.objectNode()))) {
            assertThatJson(client.post(rpc("tasks/cancel", "task-1")).body())
                    .inPath("$.result")
                    .isEqualTo("{\"resultType\":\"complete\"}");

            var updated = client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"tasks/update","params":{
                      "taskId":"task-1","inputResponses":{"approval":{"approved":true}}}}
                    """);
            assertThatJson(updated.body()).inPath("$.result").isObject();
            assertThat(connector.submittedInputs())
                    .singleElement()
                    .satisfies(input -> assertThat(input.taskId()).isEqualTo("task-1"));

            assertThatJson(client.post("""
                            {"jsonrpc":"2.0","id":1,"method":"tasks/update","params":{
                              "taskId":"missing","inputResponses":{}}}
                            """).body())
                    .inPath("$.error.message")
                    .isEqualTo("Failed to update task: " + NOT_FOUND);
            assertThatJson(client.post(rpc("tasks/cancel", "missing")).body())
                    .inPath("$.error.code")
                    .isEqualTo(-32602);
            assertThatJson(client.post(rpc("tasks/list", null)).body())
                    .as("tasks/list is legacy only")
                    .inPath("$.error.code")
                    .isEqualTo(-32601);
            assertThatJson(client.post(rpc("tasks/result", "task-1")).body())
                    .as("tasks/result is legacy only")
                    .inPath("$.error.code")
                    .isEqualTo(-32601);
        }
    }

    @Test
    void legacyMethodsAreNotFoundWhenTheConnectorLacksTheirHooks() throws Exception {
        var fixture = new TestTaskConnector().publish(working("task-1"));
        var modernOnly = TaskConnector.builder()
                .get(fixture::refresh)
                .cancel(fixture::cancel)
                .update(fixture::submitInput)
                .build();
        try (var server = startServer(modernOnly);
                var client = new Mcp20251125Client(server.port())) {
            client.initialize();

            assertThatJson(client.post(rpc("tasks/list", null)).body())
                    .inPath("$.error.code")
                    .isEqualTo(-32601);
            assertThatJson(client.post(rpc("tasks/result", "task-1")).body())
                    .inPath("$.error.code")
                    .isEqualTo(-32601);
            assertThatJson(client.post(rpc("tasks/get", "task-1")).body())
                    .inPath("$.result.status")
                    .isEqualTo("working");
        }
    }

    @Test
    void tasksExtensionIsCreatedOnlyThroughWithExtension() {
        var instance = new TasksExtensionProvider()
                .newBuilder()
                .connector(new TestTaskConnector().connector())
                .build();
        var builder = TachyonServer.builder();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> builder.withExtensions(instance))
                .withMessage("Register " + TasksExtension.class.getName()
                        + " with withExtension(TasksExtension.class, ...), not as an instance");
    }

    @Test
    void invalidConfigurationFailsTheBuild() {
        var connector = new TestTaskConnector().connector();

        assertThatIllegalStateException()
                .isThrownBy(() -> build(t -> {}))
                .withMessage("TasksExtension requires a TaskConnector");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> build(t -> t.connector(connector).pageSize(0)))
                .withMessageContaining("pageSize must be positive");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> build(t -> t.connector(connector).pollInterval(Duration.ZERO)))
                .withMessageContaining("pollInterval must be positive");
    }

    private static void build(Consumer<TasksExtension.Builder> configurer) {
        TachyonServer.builder()
                .withExtension(TasksExtension.class, configurer)
                .build()
                .close();
    }

    private static void assertError(String body, int code, String message) {
        assertThatJson(body).inPath("$.error.code").isEqualTo(code);
        assertThatJson(body).inPath("$.error.message").isEqualTo(message);
    }

    private static String rpc(String method, @Nullable String taskId) {
        var params = taskId == null ? "{}" : "{\"taskId\":\"" + taskId + "\"}";
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":" + params + "}";
    }

    private static TachyonServer startServer(TaskConnector connector) {
        return McpTestServers.start(
                builder -> builder.withExtension(TasksExtension.class, t -> t.connector(connector)), it -> {});
    }

    private static TaskSnapshot working(String taskId) {
        return TaskSnapshot.working(taskId, CREATED_AT, 1);
    }

    private static TaskSnapshot completed(String taskId) {
        return TaskSnapshot.builder()
                .from(working(taskId))
                .status(TaskState.COMPLETED)
                .result(TaskResult.completed(Map.of("output", "success")))
                .revision(2)
                .build();
    }
}
