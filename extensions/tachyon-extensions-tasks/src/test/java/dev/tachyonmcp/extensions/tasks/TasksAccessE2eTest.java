/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.tasks;

import static dev.tachyonmcp.testkit.JsonRpcResponseAssert.assertThat;
import static java.time.Duration.ofSeconds;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import dev.tachyonmcp.api.server.domain.TaskResult;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.api.server.features.tasks.TaskState;
import dev.tachyonmcp.api.server.features.tasks.TaskSupport;
import dev.tachyonmcp.api.server.features.tasks.Tasks;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.testkit.Mcp20260728Client;
import dev.tachyonmcp.testkit.McpTestClients;
import dev.tachyonmcp.testkit.McpTestServers;
import dev.tachyonmcp.testkit.TestTaskConnector;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.node.JsonNodeFactory;

/** {@code TasksExtension.tasks(server)} and {@code TasksExtension.tasks(ctx)}, over the wire. */
class TasksAccessE2eTest {

    private static final Instant CREATED_AT = Instant.parse("2026-09-27T07:00:00Z");
    private static final String NOT_REGISTERED =
            "TasksExtension is not registered: add .withExtension(TasksExtension.class, t -> t.connector(...))";

    @Test
    @Timeout(30)
    void handlerPublishesFromBackgroundJobThroughContextAccessor() throws Exception {
        var jobStore = new TestTaskConnector();
        var handlerTasks = new AtomicReference<Tasks>();
        var release = new CountDownLatch(1);
        try (var server = McpTestServers.start(
                        b -> b.withExtension(TasksExtension.class, t -> t.connector(jobStore.connector())),
                        s -> s.tools()
                                .register(
                                        tool -> tool.name("report").taskSupport(TaskSupport.REQUIRED),
                                        (ctx, request) -> {
                                            var tasks = TasksExtension.tasks(ctx);
                                            handlerTasks.set(tasks);
                                            var task = TaskSnapshot.working("report-1", CREATED_AT, 1);
                                            jobStore.publish(task);
                                            Thread.ofVirtual().start(() -> {
                                                try {
                                                    release.await();
                                                } catch (InterruptedException e) {
                                                    Thread.currentThread().interrupt();
                                                    return;
                                                }
                                                tasks.publish(TaskSnapshot.completed(
                                                        "report-1",
                                                        CREATED_AT,
                                                        Instant.now(),
                                                        2,
                                                        ToolResult.text("Report ready")));
                                            });
                                            return ToolResult.task(task);
                                        }));
                var client = tasksClient(server.port())) {

            var call = client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"report","arguments":{}}}
                    """);
            assertThat(call).isSuccess().hasResultType("task");
            assertThatJson(call.body()).inPath("$.result.taskId").isEqualTo("report-1");
            assertThatJson(call.body()).inPath("$.result.status").isEqualTo("working");

            var serverTasks = TasksExtension.tasks(server);
            assertThat(handlerTasks.get())
                    .as("the context accessor reaches the same facade as the server accessor")
                    .isSameAs(serverTasks)
                    .isSameAs(
                            server.extension(TasksExtension.class).orElseThrow().tasks());

            var stream = client.openPostStream(null, """
                    {"jsonrpc":"2.0","id":2,"method":"subscriptions/listen",
                     "params":{"notifications":{"taskIds":["report-1"]}}}
                    """);
            var ack = stream.await(f -> f.data().contains("notifications/subscriptions/acknowledged"), ofSeconds(5));
            assertThatJson(ack.json().path("params").toString())
                    .inPath("$.notifications.taskIds")
                    .isArray()
                    .containsExactly("report-1");
            release.countDown();

            var completed = stream.await(
                    f -> f.data().contains("notifications/tasks") && f.data().contains("\"completed\""), ofSeconds(5));
            assertThatJson(completed.json().path("params").toString())
                    .isObject()
                    .containsEntry("taskId", "report-1")
                    .containsEntry("status", "completed");
            assertThatJson(completed.json().path("params").toString())
                    .inPath("$.result.content[0].text")
                    .isEqualTo("Report ready");
            assertThat(serverTasks.get("report-1"))
                    .as("the background publish reached the server's cache")
                    .isNotNull()
                    .extracting(TaskSnapshot::status)
                    .isEqualTo(TaskState.COMPLETED);
        }
    }

    @Test
    @Timeout(30)
    void publishBuildsTheSnapshotFromABuilderConsumer() throws Exception {
        var jobStore = new TestTaskConnector().publish(TaskSnapshot.working("charge-1", CREATED_AT, 1));
        try (var server = McpTestServers.start(
                        b -> b.withExtension(TasksExtension.class, t -> t.connector(jobStore.connector())), s -> {});
                var client = tasksClient(server.port())) {
            var stream = client.openPostStream(null, """
                    {"jsonrpc":"2.0","id":1,"method":"subscriptions/listen",
                     "params":{"notifications":{"taskIds":["charge-1"]}}}
                    """);
            stream.await(f -> f.data().contains("notifications/subscriptions/acknowledged"), ofSeconds(5));
            var tasks = TasksExtension.tasks(server);

            var published = tasks.publish(s -> s.taskId("charge-1")
                    .status(TaskState.WORKING)
                    .statusMessage("Charging card")
                    .createdAt(CREATED_AT)
                    .lastUpdatedAt(CREATED_AT.plusSeconds(1))
                    .revision(2));

            assertThat(published.taskId()).isEqualTo("charge-1");
            assertThat(published.statusMessage()).isEqualTo("Charging card");
            assertThat(published.revision()).isEqualTo(2);
            assertThat(tasks.get("charge-1")).isEqualTo(published);
            var working = stream.await(f -> f.data().contains("Charging card"), ofSeconds(5));
            assertThatJson(working.json().path("params").toString())
                    .isObject()
                    .containsEntry("taskId", "charge-1")
                    .containsEntry("status", "working")
                    .containsEntry("statusMessage", "Charging card");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> tasks.publish(s -> s.from(published)
                            .status(TaskState.COMPLETED)
                            .statusMessage(null)
                            .revision(3)))
                    .withMessage("COMPLETED snapshot requires a TaskResult.Completed result");
            assertThat(tasks.get("charge-1"))
                    .as("a snapshot the builder rejects is never published")
                    .isEqualTo(published);

            var completed = tasks.publish(s -> s.next(published)
                    .status(TaskState.COMPLETED)
                    .statusMessage(null)
                    .result(TaskResult.completed(ToolResult.text("Charged")))
                    .lastUpdatedAt(CREATED_AT.plusSeconds(2)));

            assertThat(completed.revision())
                    .as("next(previous) copies previous and bumps its revision")
                    .isEqualTo(published.revision() + 1);
            assertThat(completed.createdAt()).isEqualTo(published.createdAt());
            assertThat(tasks.get("charge-1")).isEqualTo(completed);
            stream.await(f -> f.data().contains("\"completed\"") && f.data().contains("Charged"), ofSeconds(5));
        }
    }

    @Test
    void serverAccessorFailsFastWithoutTasksExtension() {
        try (var server = McpTestServers.start(b -> {}, s -> {})) {
            assertThat(server.extension(TasksExtension.class)).isEmpty();
            assertThatIllegalStateException()
                    .isThrownBy(() -> TasksExtension.tasks(server))
                    .withMessage(NOT_REGISTERED);
        }
    }

    @Test
    void contextAccessorWithoutTasksExtensionIsAnInternalErrorNotAClientError() throws Exception {
        var thrown = new AtomicReference<Throwable>();
        try (var server = McpTestServers.start(
                        b -> {},
                        s -> s.tools().register(tool -> tool.name("needs-tasks"), (ctx, request) -> {
                            try {
                                TasksExtension.tasks(ctx);
                            } catch (IllegalStateException e) {
                                thrown.set(e);
                                throw e;
                            }
                            return ToolResult.text("unreachable");
                        }));
                var client = McpTestClients.latest(server.port())) {

            var call = client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"needs-tasks","arguments":{}}}
                    """);

            assertThat(call).isJsonRpcError().hasErrorCode(-32603);
            assertThat(thrown.get()).isInstanceOf(IllegalStateException.class).hasMessage(NOT_REGISTERED);
        }
    }

    private static Mcp20260728Client tasksClient(int port) {
        return McpTestClients.latest(port)
                .withExtensions(Map.of(TasksExtension.ID, JsonNodeFactory.instance.objectNode()));
    }
}
