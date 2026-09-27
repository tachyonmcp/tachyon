/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.tasks;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import dev.tachyonmcp.api.server.domain.ServerError;
import dev.tachyonmcp.api.server.domain.TaskResult;
import dev.tachyonmcp.api.server.features.tasks.TaskConnector;
import dev.tachyonmcp.api.server.features.tasks.TaskGetFn;
import dev.tachyonmcp.api.server.features.tasks.TaskNotFoundException;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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

    // 2025-11-25 Tasks § Result Retrieval: tasks/result MUST block until the task is terminal, then
    // return exactly what the underlying request would have returned.
    @Test
    void legacyResultWithoutAwaitHookPollsGetUntilTerminal() throws Exception {
        var polls = new AtomicInteger();
        var failure = new ServerError(ServerError.Kind.INTERNAL_ERROR, "workflow crashed");
        var modernOnly = TaskConnector.builder()
                .get((ctx, request) -> switch (request.taskId()) {
                    case "task-1" ->
                        polls.incrementAndGet() < 3 ? pollable(working("task-1")) : pollable(completed("task-1"));
                    case "broken" -> TaskSnapshot.failed("broken", CREATED_AT, CREATED_AT, 2, failure);
                    default -> throw new TaskNotFoundException(request.taskId());
                })
                .cancel((ctx, request) -> {})
                .update((ctx, request) -> {})
                .build();
        try (var server = startServer(modernOnly);
                var client = new Mcp20251125Client(server.port())) {
            client.initialize();

            // language=json
            assertThatJson(client.post(rpc("tasks/result", "task-1")).body()).isEqualTo("""
                    {
                      "jsonrpc":"2.0",
                      "id":1,
                      "result":{
                        "content":[{"type":"text","text":"{\\"output\\":\\"success\\"}"}],
                        "structuredContent":{"output":"success"},
                        "_meta":{"io.modelcontextprotocol/related-task":{"taskId":"task-1"}}
                      }
                    }
                    """);
            assertThat(polls).as("blocked through two working polls").hasValue(3);

            // language=json
            assertThatJson(client.post(rpc("tasks/result", "broken")).body()).isEqualTo("""
                    {"jsonrpc":"2.0","id":1,"error":{"code":-32603,"message":"workflow crashed"}}
                    """);
            assertError(
                    client.post(rpc("tasks/result", "missing")).body(),
                    -32602,
                    "Failed to retrieve task: " + NOT_FOUND);
            assertThatJson(client.post(rpc("tasks/list", null)).body())
                    .as("tasks/list stays optional")
                    .inPath("$.error.code")
                    .isEqualTo(-32601);
        }
    }

    // 2025-11-25 Tasks § TTL: after ttl elapses the receiver MAY delete the task; example error
    // "Failed to retrieve task: Task has expired".
    @Test
    void legacyResultStopsWaitingOnceTheTaskTtlHasElapsed() throws Exception {
        var expiredWorking = TaskSnapshot.builder()
                .from(working("task-1"))
                .ttl(Duration.ofMinutes(1))
                .build();
        var connector = modernOnly((ctx, request) -> expiredWorking);
        try (var server = startServer(connector);
                var client = new Mcp20251125Client(server.port())) {
            client.initialize();

            // language=json
            assertThatJson(client.post(rpc("tasks/result", "task-1")).body()).isEqualTo("""
                    {"jsonrpc":"2.0","id":1,"error":{"code":-32602,"message":"Failed to retrieve task: Task has expired"}}
                    """);
        }
    }

    @Test
    void legacyResultPollsAtTheConfiguredInterval() throws Exception {
        var polls = new AtomicInteger();
        var connector =
                modernOnly((ctx, request) -> polls.incrementAndGet() < 10 ? working("task-1") : completed("task-1"));
        try (var server = startServer(connector, t -> t.resultPollInterval(Duration.ofMillis(20)));
                var client = new Mcp20251125Client(server.port())) {
            client.initialize();
            var started = System.nanoTime();

            assertThatJson(client.post(rpc("tasks/result", "task-1")).body())
                    .inPath("$.result.structuredContent")
                    .isEqualTo("{\"output\":\"success\"}");
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .as("9 pauses at resultPollInterval, not the 1s default")
                    .isLessThan(Duration.ofSeconds(5));
            assertThat(polls).hasValue(10);
        }
    }

    // 2025-11-25 Tasks § Task Cancellation 1: reject an already-terminal task with -32602.
    @Test
    void legacyCancelOfTerminalTaskIsRejectedWithoutCallingTheConnector() throws Exception {
        var cancels = new AtomicInteger();
        var connector = TaskConnector.builder()
                .get((ctx, request) -> completed("task-1"))
                .cancel((ctx, request) -> cancels.incrementAndGet())
                .update((ctx, request) -> {})
                .build();
        try (var server = startServer(connector);
                var client = new Mcp20251125Client(server.port())) {
            client.initialize();

            // language=json
            assertThatJson(client.post(rpc("tasks/cancel", "task-1")).body()).isEqualTo("""
                    {"jsonrpc":"2.0","id":1,"error":{"code":-32602,
                      "message":"Cannot cancel task: already in terminal status 'completed'"}}
                    """);
            assertThat(cancels).hasValue(0);
        }
    }

    // 2025-11-25 Tasks § Task Cancellation 2: transition to cancelled before sending the response,
    // even when the connector's cancel only requests it.
    @Test
    void legacyCancelWaitsUntilAFireAndForgetConnectorSettles() throws Exception {
        var requested = new AtomicBoolean();
        var pollsAfterCancel = new AtomicInteger();
        var connector = TaskConnector.builder()
                .get((ctx, request) -> requested.get() && pollsAfterCancel.incrementAndGet() >= 3
                        ? pollable(TaskSnapshot.builder()
                                .from(working("task-1"))
                                .status(TaskState.CANCELLED)
                                .revision(2)
                                .build())
                        : pollable(working("task-1")))
                .cancel((ctx, request) -> requested.set(true))
                .update((ctx, request) -> {})
                .build();
        try (var server = startServer(connector);
                var client = new Mcp20251125Client(server.port())) {
            client.initialize();

            // language=json
            assertThatJson(client.post(rpc("tasks/cancel", "task-1")).body()).isEqualTo("""
                    {"jsonrpc":"2.0","id":1,"result":{
                      "taskId":"task-1",
                      "status":"cancelled",
                      "createdAt":"2026-08-27T07:00:00Z",
                      "lastUpdatedAt":"2026-08-27T07:00:00Z",
                      "ttl":null,
                      "pollInterval":10
                    }}
                    """);
            assertThat(pollsAfterCancel)
                    .as("answered only once get reported cancelled")
                    .hasValue(3);
        }
    }

    // A cancel that loses the race to completion: the task is terminal, not cancelled.
    @Test
    void legacyCancelThatLosesTheRaceIsRejectedAsTerminal() throws Exception {
        var requested = new AtomicBoolean();
        var connector = TaskConnector.builder()
                .get((ctx, request) -> requested.get() ? completed("task-1") : working("task-1"))
                .cancel((ctx, request) -> requested.set(true))
                .update((ctx, request) -> {})
                .build();
        try (var server = startServer(connector);
                var client = new Mcp20251125Client(server.port())) {
            client.initialize();

            // language=json
            assertThatJson(client.post(rpc("tasks/cancel", "task-1")).body()).isEqualTo("""
                    {"jsonrpc":"2.0","id":1,"error":{"code":-32602,
                      "message":"Cannot cancel task: already in terminal status 'completed'"}}
                    """);
        }
    }

    @Test
    void resultPollIntervalMustBePositive() {
        var connector = new TestTaskConnector().connector();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> build(t -> t.connector(connector).resultPollInterval(Duration.ofMillis(-1))))
                .withMessage("resultPollInterval must be positive, got: PT-0.001S");
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
        return startServer(connector, t -> {});
    }

    private static TachyonServer startServer(TaskConnector connector, Consumer<TasksExtension.Builder> configurer) {
        return McpTestServers.start(
                builder -> builder.withExtension(TasksExtension.class, t -> configurer.accept(t.connector(connector))),
                it -> {});
    }

    private static TaskConnector modernOnly(TaskGetFn get) {
        return TaskConnector.builder()
                .get(get)
                .cancel((ctx, request) -> {})
                .update((ctx, request) -> {})
                .build();
    }

    private static TaskSnapshot working(String taskId) {
        return TaskSnapshot.working(taskId, CREATED_AT, 1);
    }

    private static TaskSnapshot pollable(TaskSnapshot snapshot) {
        return TaskSnapshot.builder()
                .from(snapshot)
                .pollInterval(Duration.ofMillis(10))
                .build();
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
