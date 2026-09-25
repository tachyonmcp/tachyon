/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2026_07_28;

import static dev.tachyonmcp.testkit.JsonRpcResponseAssert.assertThat;
import static java.time.Duration.ofSeconds;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.api.server.features.tasks.TaskSupport;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.features.tasks.TasksExtension;
import dev.tachyonmcp.e2e.mcp.AbstractStatelessMcpE2eTest;
import dev.tachyonmcp.testkit.Mcp20260728Client;
import dev.tachyonmcp.testkit.McpClient;
import dev.tachyonmcp.testkit.McpTestClients;
import dev.tachyonmcp.testkit.SseFrame;
import dev.tachyonmcp.testkit.SseStream;
import dev.tachyonmcp.testkit.TestTaskConnector;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Tasks extension without sessions: every task is ownerless, so {@code notifications/tasks} reaches
 * exactly the {@code subscriptions/listen} streams that opted into its id, however the task was
 * published. The server is shared by the class, so every test uses task ids of its own.
 */
class TaskNotificationsTest extends AbstractStatelessMcpE2eTest<McpClient> {

    private static final Instant CREATED_AT = Instant.parse("2026-09-24T07:00:00Z");

    @Override
    protected Mcp20260728Client createTestClient() {
        return createTestClient(port);
    }

    @Override
    protected Mcp20260728Client createTestClient(int port) {
        return McpTestClients.latest(port);
    }

    @Override
    protected void startDefaultServer() {
        var connector = new TestTaskConnector().connector();
        startServer(builder -> builder.capabilities(c -> c.tools(true).tasks(connector)), registrar -> {
            registrar
                    .tools()
                    .registerAsync(b -> b.name("book-async").taskSupport(TaskSupport.REQUIRED), (context, request) -> {
                        var snapshot = TaskSnapshot.working(request.arguments().stringOr("taskId", ""), CREATED_AT, 1);
                        var dispatchThread = Thread.currentThread();
                        var result = new CompletableFuture<ToolResult>();
                        // Completes only after the dispatch thread ends, so the task is mapped off it.
                        Thread.ofVirtual().start(() -> {
                            try {
                                dispatchThread.join();
                                result.complete(ToolResult.task(snapshot));
                            } catch (InterruptedException e) {
                                result.completeExceptionally(e);
                            }
                        });
                        return result;
                    });
        });
    }

    @Test
    @Timeout(30)
    void asyncTaskToolHandoffReachesOnlyItsTaskSubscribers() throws Exception {
        try (var client = tasksClient()) {
            var subscriber = listen(client, 1, "async-task");
            var bystander = listen(client, 2, "async-fence");

            var response = client.sendRpc("""
                    {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
                      "name":"book-async","arguments":{"taskId":"async-task"}}}
                    """);
            assertThat(response).isSuccess().hasResultType("task");
            assertThatJson(response.body()).inPath("$.result.taskId").isEqualTo("async-task");

            var status = subscriber.await(f -> isTaskNotification(f, "async-task"), ofSeconds(5));
            assertThatJson(status.json().path("params").toString())
                    .isObject()
                    .containsEntry("taskId", "async-task")
                    .containsEntry("status", "working");

            assertNothingBefore(bystander, "async-fence", "async-task");
        }
    }

    @Test
    @Timeout(30)
    void publishOfATaskCreatedElsewhereReachesItsSubscribersUncached() throws Exception {
        try (var client = tasksClient()) {
            var subscriber = listen(client, 1, "remote-task");
            var bystander = listen(client, 2, "remote-fence");

            // E.g. a connector callback on a node that never saw the task-augmented tool call.
            server.tasks().publish(TaskSnapshot.working("remote-task", CREATED_AT, 1));

            var status = subscriber.await(f -> isTaskNotification(f, "remote-task"), ofSeconds(5));
            assertThatJson(status.json().path("params").toString())
                    .isObject()
                    .containsEntry("taskId", "remote-task")
                    .containsEntry("status", "working");
            assertThat(server.tasks().get("remote-task"))
                    .as("only a task-augmented tool call creates a cached task")
                    .isNull();

            assertNothingBefore(bystander, "remote-fence", "remote-task");
        }
    }

    @Test
    void getAndCancelOnUnknownTaskReturnInvalidParams() throws Exception {
        try (var client = tasksClient()) {
            var get = client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"tasks/get","params":{"taskId":"never-created"}}
                    """);
            assertThat(get).isJsonRpcError().hasErrorCode(-32602).hasErrorMessageContaining("Task not found");

            var cancel = client.post("""
                    {"jsonrpc":"2.0","id":2,"method":"tasks/cancel","params":{"taskId":"never-created"}}
                    """);
            assertThat(cancel).isJsonRpcError().hasErrorCode(-32602).hasErrorMessageContaining("Task not found");
            assertThat(server.tasks().get("never-created"))
                    .as("a failed lookup caches nothing")
                    .isNull();
        }
    }

    private Mcp20260728Client tasksClient() {
        return createTestClient().withExtensions(Map.of(TasksExtension.ID, JsonNodeFactory.instance.objectNode()));
    }

    private static SseStream listen(Mcp20260728Client client, int id, String taskId) throws Exception {
        var stream = client.openPostStream(null, """
                {"jsonrpc":"2.0","id":%d,"method":"subscriptions/listen",
                 "params":{"notifications":{"taskIds":["%s"]}}}
                """.formatted(id, taskId));
        stream.await(f -> f.data().contains("notifications/subscriptions/acknowledged"), ofSeconds(5));
        return stream;
    }

    /**
     * Publishes the task {@code stream} listens to and waits for it. Deliveries on one stream keep
     * their order, so a leaked {@code leakedTaskId} notification would arrive before this one.
     */
    private void assertNothingBefore(SseStream stream, String fenceTaskId, String leakedTaskId) {
        server.tasks().publish(TaskSnapshot.working(fenceTaskId, CREATED_AT, 1));
        stream.await(f -> isTaskNotification(f, fenceTaskId), ofSeconds(5));
        assertThat(stream.received(f -> isTaskNotification(f, leakedTaskId)))
                .as("a stream that did not opt into %s must not receive it", leakedTaskId)
                .isEmpty();
    }

    private static boolean isTaskNotification(SseFrame frame, String taskId) {
        if (frame.data().isBlank()) return false;
        var json = frame.json();
        return "notifications/tasks".equals(json.path("method").asString())
                && taskId.equals(json.path("params").path("taskId").asString());
    }
}
