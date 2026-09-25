/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2025_11_25;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.server.domain.TaskResult;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.api.server.features.tasks.TaskState;
import dev.tachyonmcp.api.server.features.tasks.TaskSupport;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.testkit.TestTaskConnector;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TasksExtensionTest extends AbstractStatefulMcpE2eTest {

    private TestTaskConnector taskEngine;
    private TaskSnapshot initial;

    @Override
    protected void startDefaultServer() {
        initial = TaskSnapshot.working("legacy-workflow", Instant.parse("2026-08-27T07:00:00Z"), 1);
        taskEngine = new TestTaskConnector().publish(initial);
        startServer(builder -> builder.capabilities(c -> c.tasks(taskEngine.connector())), registrar -> {
            registrar
                    .tools()
                    .register(
                            b -> b.name("book").taskSupport(TaskSupport.REQUIRED),
                            (context, request) -> ToolResult.task(initial));
            // A taskId of its own -- keeps the progress token it captures independent of
            // "book"/"legacy-workflow", which other tests in this class also publish to.
            registrar
                    .tools()
                    .register(
                            b -> b.name("book-for-progress").taskSupport(TaskSupport.REQUIRED),
                            (context, request) -> ToolResult.task(
                                    TaskSnapshot.working("legacy-progress", Instant.parse("2026-08-27T07:00:00Z"), 1)));
            registrar.tools().register(b -> b.name("notify-progress"), (context, request) -> {
                server.tasks().reportProgress("legacy-progress", 0.5, 1.0, "halfway");
                return ToolResult.text("ok");
            });
            registrar
                    .tools()
                    .register(
                            b -> b.name("notify-legacy-task-status").taskSupport(TaskSupport.REQUIRED),
                            (context, request) -> ToolResult.task(
                                    TaskSnapshot.working("legacy-notify", Instant.parse("2026-08-27T07:00:00Z"), 1)));
        });
    }

    @Test
    void taskAugmentedToolReturnsConnectorIdentity() throws Exception {
        try (var client = createTestClient()) {
            client.initialize();
            var response = client.sendRpc("""
                    {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                      "name":"book","arguments":{},"task":{}}}
                    """);

            assertThatJson(response.body()).inPath("$.result.task.taskId").isEqualTo("legacy-workflow");
            assertThatJson(response.body()).inPath("$.result.task.status").isEqualTo("working");
        }
    }

    @Test
    void resultReadsTerminalSnapshotFromConnector() throws Exception {
        var resultTaskId = "legacy-result";
        try (var client = createTestClient()) {
            client.initialize();
            taskEngine.publish(TaskSnapshot.builder()
                    .from(initial)
                    .taskId(resultTaskId)
                    .status(TaskState.COMPLETED)
                    .lastUpdatedAt(Instant.parse("2026-08-27T07:00:01Z"))
                    .result(TaskResult.completed(Map.of("bookingId", "booking-1")))
                    .revision(2)
                    .build());

            var result = client.sendRpc("""
                    {"jsonrpc":"2.0","id":3,"method":"tasks/result","params":{"taskId":"%s"}}
                    """.formatted(resultTaskId));
            assertThatJson(result.body())
                    .inPath("$.result.structuredContent.bookingId")
                    .isEqualTo("booking-1");
            assertThat(taskEngine.awaitedTaskIds()).containsExactly(resultTaskId);
        }
    }

    @Test
    void notifiesTaskStatusOverSessionForLegacyClient() throws Exception {
        try (var client = createTestClient()) {
            client.initialize();

            client.sendRpc("""
                    {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                      "name":"notify-legacy-task-status","arguments":{},"task":{}}}
                    """);

            client.awaitNotification("notifications/tasks/status").satisfies(params -> {
                assertThat(params.path("taskId").asString()).isEqualTo("legacy-notify");
                assertThat(params.path("status").asString()).isEqualTo("working");
            });
        }
    }

    @Test
    void reportsProgressForAConnectorDrivenTask() throws Exception {
        try (var client = createTestClient()) {
            client.initialize();

            var bookResponse = client.sendRpc("""
                    {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                      "name":"book-for-progress","arguments":{},"task":{},
                      "_meta":{"progressToken":"tok-1"}}}
                    """);
            assertThatJson(bookResponse.body()).inPath("$.result.task.taskId").isEqualTo("legacy-progress");

            client.sendRpc("""
                    {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
                      "name":"notify-progress","arguments":{}}}
                    """);

            client.awaitNotification("notifications/progress", Duration.ofSeconds(5))
                    .satisfies(params -> {
                        assertThat(params.path("progressToken").asString()).isEqualTo("tok-1");
                        assertThat(params.path("progress").asDouble()).isEqualTo(0.5);
                        assertThat(params.path("total").asDouble()).isEqualTo(1.0);
                        assertThat(params.path("message").asString()).isEqualTo("halfway");
                    });
        }
    }
}
