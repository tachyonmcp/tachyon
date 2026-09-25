/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2025_11_25;

import static dev.tachyonmcp.testkit.McpHttpResponseAssert.assertThatResponse;
import static java.time.Duration.ofSeconds;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.features.PaginatedResult;
import dev.tachyonmcp.api.server.features.tasks.TaskConnector;
import dev.tachyonmcp.api.server.features.tasks.TaskNotFoundException;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.api.server.features.tasks.TaskSupport;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.features.tasks.TasksExtension;
import dev.tachyonmcp.core.server.json.JsonUtils;
import dev.tachyonmcp.testkit.Mcp20251125Client;
import dev.tachyonmcp.testkit.Mcp20260728Client;
import dev.tachyonmcp.testkit.McpTestClients;
import dev.tachyonmcp.testkit.SseFrame;
import dev.tachyonmcp.testkit.SseStream;
import dev.tachyonmcp.testkit.TestTaskConnector;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;

/**
 * Tachyon does not decide who reaches a task: every {@code tasks/*} call goes to the
 * {@code TaskConnector} with the caller's {@code InteractionContext}. Without an authorization
 * context the task id is a bearer capability (2025-11-25 Tasks § Security). This connector scopes
 * each booked task to the session that booked it: the recipe an application uses for session
 * isolation, documented in {@code docs/features/tasks.md}. The same {@code get} check authorizes each
 * task id a {@code subscriptions/listen} stream names (ext-tasks 2026-07-28 § Security: authorize
 * every task-related request); the acknowledgment lists only the ids it allowed.
 */
class TaskConnectorAccessTest extends AbstractStatefulMcpE2eTest {

    private static final Instant CREATED_AT = Instant.parse("2026-09-24T07:00:00Z");

    private final TestTaskConnector taskEngine = new TestTaskConnector();
    private final Map<String, String> bookingSessions = new ConcurrentHashMap<>();
    private final List<String> accessChecks = new CopyOnWriteArrayList<>();

    @Override
    protected void startDefaultServer() {
        var connector = sessionScopedConnector();
        startServer(
                builder -> builder.capabilities(c -> c.tasks(connector)),
                registrar -> registrar
                        .tools()
                        .register(b -> b.name("book").taskSupport(TaskSupport.REQUIRED), (context, request) -> {
                            var taskId = request.arguments().stringOr("taskId", "");
                            var sessionId = context.sessionId();
                            var shared = request.arguments().boolOpt("shared").orElse(false);
                            if (sessionId != null && !shared) {
                                bookingSessions.putIfAbsent(taskId, sessionId);
                            }
                            return ToolResult.task(TaskSnapshot.working(taskId, CREATED_AT, 1));
                        }));
    }

    @SuppressWarnings("deprecation")
    private TaskConnector sessionScopedConnector() {
        return TaskConnector.builder()
                .get((ctx, request) -> {
                    authorize(ctx, request.taskId());
                    return taskEngine.refresh(ctx, request);
                })
                .cancel((ctx, request) -> {
                    authorize(ctx, request.taskId());
                    taskEngine.cancel(ctx, request);
                })
                .update((ctx, request) -> {
                    authorize(ctx, request.taskId());
                    taskEngine.submitInput(ctx, request);
                })
                .list((ctx, request) -> {
                    var page = taskEngine.list(ctx, request);
                    var visible = page.items().stream()
                            .filter(task -> visibleTo(ctx, task.taskId()))
                            .toList();
                    return PaginatedResult.of(visible, page.nextCursor(), page.cursorValid());
                })
                .awaitResult((ctx, request) -> {
                    authorize(ctx, request.taskId());
                    return taskEngine.awaitResult(ctx, request);
                })
                .build();
    }

    private void authorize(InteractionContext ctx, String taskId) throws TaskNotFoundException {
        accessChecks.add(taskId + "@" + ctx.sessionId());
        if (!visibleTo(ctx, taskId)) {
            throw new TaskNotFoundException(taskId);
        }
    }

    private boolean visibleTo(InteractionContext ctx, String taskId) {
        var bookedBy = bookingSessions.get(taskId);
        return bookedBy == null || bookedBy.equals(ctx.sessionId());
    }

    @Test
    @Timeout(30)
    void theConnectorDecidesWhoReachesATask() throws Exception {
        try (var booker = createTestClient();
                var other = createTestClient()) {
            booker.initialize();
            var otherSession = other.initialize();
            taskEngine.publish(TaskSnapshot.working("scoped-task", CREATED_AT, 1));
            var booked = booker.sendRpc("""
                    {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                      "name":"book","arguments":{"taskId":"scoped-task"},"task":{}}}
                    """);
            assertThatJson(booked.body()).inPath("$.result.task.taskId").isEqualTo("scoped-task");

            for (var method : List.of("tasks/get", "tasks/cancel", "tasks/result")) {
                var denied = other.sendRpc("""
                        {"jsonrpc":"2.0","id":3,"method":"%s","params":{"taskId":"scoped-task"}}
                        """.formatted(method));
                var unknown = other.sendRpc("""
                        {"jsonrpc":"2.0","id":3,"method":"%s","params":{"taskId":"never-created"}}
                        """.formatted(method));
                assertThatResponse(denied)
                        .as(method)
                        .isJsonRpcError()
                        .hasErrorCode(-32602)
                        .hasErrorMessageContaining("Task not found");
                assertThatJson(denied.body())
                        .as("%s: the connector's refusal reads like an unknown id", method)
                        .isEqualTo(unknown.body());
            }
            assertThat(accessChecks)
                    .as("Tachyon asks the connector instead of deciding access itself")
                    .contains("scoped-task@" + otherSession);
            assertThat(taskEngine.cancelledTaskIds()).doesNotContain("scoped-task");

            var otherList = other.sendRpc("""
                    {"jsonrpc":"2.0","id":4,"method":"tasks/list","params":{}}
                    """);
            assertThatJson(otherList.body())
                    .inPath("$.result.tasks[*].taskId")
                    .isArray()
                    .doesNotContain("scoped-task");
            var bookerList = booker.sendRpc("""
                    {"jsonrpc":"2.0","id":4,"method":"tasks/list","params":{}}
                    """);
            assertThatJson(bookerList.body())
                    .inPath("$.result.tasks[*].taskId")
                    .isArray()
                    .contains("scoped-task");

            taskEngine.publish(TaskSnapshot.builder()
                    .from(TaskSnapshot.working("scoped-task", CREATED_AT, 2))
                    .statusMessage("refreshed")
                    .build());
            var get = booker.sendRpc("""
                    {"jsonrpc":"2.0","id":5,"method":"tasks/get","params":{"taskId":"scoped-task"}}
                    """);
            assertThatJson(get.body()).inPath("$.result.statusMessage").isEqualTo("refreshed");
            // The refreshed revision is pushed along the task's route: the booker's session.
            booker.awaitNotification(
                    "notifications/tasks/status",
                    params -> "refreshed".equals(params.path("statusMessage").asString()),
                    ofSeconds(5));
        }
    }

    @Test
    @Timeout(30)
    void refusedListenerNeverReceivesTheTaskEvenAfterEviction() throws Exception {
        try (var booker = createTestClient();
                var listener = tasksListener()) {
            booker.initialize();
            taskEngine.publish(TaskSnapshot.working("private-task", CREATED_AT, 1));
            taskEngine.publish(TaskSnapshot.working("open-task", CREATED_AT, 1));
            book(booker, "private-task", false);

            var stream = listen(listener, 7, "private-task", "open-task");
            assertThat(acknowledgedTaskIds(stream))
                    .as("the ack lists only the task ids the connector let this listener read")
                    .containsExactly("open-task");
            assertThat(accessChecks).contains("private-task@null", "open-task@null");

            server.tasks().publish(TaskSnapshot.working("private-task", CREATED_AT, 2));
            // Evicted, e.g. by ttl: the route is gone and the next publish caches the task unrouted.
            assertThat(server.tasks().remove("private-task")).isTrue();
            server.tasks()
                    .publish(TaskSnapshot.completed(
                            "private-task", CREATED_AT, CREATED_AT, 3, ToolResult.text("private result")));
            server.tasks()
                    .publish(TaskSnapshot.completed(
                            "open-task", CREATED_AT, CREATED_AT, 2, ToolResult.text("open result")));

            // This later event fences preceding writes on the same stream; no timed absence check.
            var open = stream.await(
                    f -> isTaskNotification(f, "open-task") && f.data().contains("open result"), ofSeconds(5));
            // language=JSON
            assertThatJson(open.data()).isEqualTo("""
                    {"jsonrpc":"2.0","method":"notifications/tasks","params":{
                      "taskId":"open-task","status":"completed",
                      "createdAt":"2026-09-24T07:00:00Z","lastUpdatedAt":"2026-09-24T07:00:00Z",
                      "ttlMs":null,"result":{
                        "content":[{"type":"text","text":"open result"}],"resultType":"complete"}}}
                    """);
            assertThat(stream.received(f -> isTaskNotification(f, "private-task")))
                    .as("a listener the connector refused never receives the task, routed or not")
                    .isEmpty();
        }
    }

    @Test
    @Timeout(30)
    void authorizedListenerReceivesARoutedTaskAlongsideItsSession() throws Exception {
        try (var booker = createTestClient();
                var listener = tasksListener()) {
            booker.initialize();
            var bookerStream = booker.openGetStream(null);
            bookerStream.awaitFirstEventId(ofSeconds(5));
            taskEngine.publish(TaskSnapshot.working("shared-task", CREATED_AT, 1));
            book(booker, "shared-task", true);

            var stream = listen(listener, 8, "shared-task");
            assertThat(acknowledgedTaskIds(stream)).containsExactly("shared-task");

            server.tasks()
                    .publish(TaskSnapshot.builder()
                            .from(TaskSnapshot.working("shared-task", CREATED_AT, 2))
                            .statusMessage("shared")
                            .build());

            bookerStream.await(
                    f -> isNotification(f, "notifications/tasks/status", "shared-task")
                            && f.data().contains("\"shared\""),
                    ofSeconds(5));
            stream.await(f -> isTaskNotification(f, "shared-task") && f.data().contains("\"shared\""), ofSeconds(5));
        }
    }

    private static void book(Mcp20251125Client client, String taskId, boolean shared) throws Exception {
        var booked = client.sendRpc("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                  "name":"book","arguments":{"taskId":"%s","shared":%s},"task":{}}}
                """.formatted(taskId, shared));
        assertThatJson(booked.body()).inPath("$.result.task.taskId").isEqualTo(taskId);
    }

    private Mcp20260728Client tasksListener() {
        return McpTestClients.latest(port).withExtensions(Map.of(TasksExtension.ID, JsonUtils.parseJsonNode("{}")));
    }

    private static SseStream listen(Mcp20260728Client client, int id, String... taskIds) throws Exception {
        var ids = String.join("\",\"", taskIds);
        // language=JSON
        var stream = client.openPostStream(null, """
                {"jsonrpc":"2.0","id":%d,"method":"subscriptions/listen",
                  "params":{"notifications":{"taskIds":["%s"]}}}
                """.formatted(id, ids));
        stream.await(f -> f.data().contains("notifications/subscriptions/acknowledged"), ofSeconds(5));
        return stream;
    }

    private static List<String> acknowledgedTaskIds(SseStream stream) {
        var ack = stream.received(f -> f.data().contains("notifications/subscriptions/acknowledged"))
                .getFirst();
        var taskIds = ack.json().path("params").path("notifications").path("taskIds");
        return taskIds.isArray() ? taskIds.valueStream().map(JsonNode::asString).toList() : List.of();
    }

    private static boolean isTaskNotification(SseFrame frame, String taskId) {
        return isNotification(frame, "notifications/tasks", taskId);
    }

    private static boolean isNotification(SseFrame frame, String method, String taskId) {
        if (frame.data().isBlank()) return false;
        var json = frame.json();
        return method.equals(json.path("method").asString())
                && taskId.equals(json.path("params").path("taskId").asString());
    }
}
