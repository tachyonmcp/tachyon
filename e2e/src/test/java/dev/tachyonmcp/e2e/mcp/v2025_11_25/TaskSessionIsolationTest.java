/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2025_11_25;

import static dev.tachyonmcp.testkit.McpHttpResponseAssert.assertThatResponse;
import static java.time.Duration.ofSeconds;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.api.server.features.tasks.TaskSupport;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.testkit.Mcp20251125Client;
import dev.tachyonmcp.testkit.SseFrame;
import dev.tachyonmcp.testkit.SseStream;
import dev.tachyonmcp.testkit.TestTaskConnector;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Task notifications reach the session that owns the task, never a bystander session. The server is
 * shared by the class, so every test uses task ids of its own.
 */
class TaskSessionIsolationTest extends AbstractStatefulMcpE2eTest {

    private static final Instant CREATED_AT = Instant.parse("2026-09-24T07:00:00Z");

    private final AtomicInteger fences = new AtomicInteger();
    private final TestTaskConnector taskEngine = new TestTaskConnector();

    @Override
    protected void startDefaultServer() {
        var connector = taskEngine.connector();
        startServer(builder -> builder.capabilities(c -> c.tasks(connector).logging()), registrar -> {
            registrar.tools().register(b -> b.name("publish-server-scoped"), (context, request) -> {
                server.tasks().publish(TaskSnapshot.working("orphan", CREATED_AT, 1));
                return ToolResult.text("published");
            });
            registrar
                    .tools()
                    .registerAsync(b -> b.name("book-async").taskSupport(TaskSupport.REQUIRED), (context, request) -> {
                        var snapshot = TaskSnapshot.working(
                                request.arguments().stringOr("taskId", ""),
                                CREATED_AT,
                                request.arguments().intOpt("revision").orElse(1));
                        if (request.arguments().boolOpt("publishFirst").orElse(false)) {
                            server.tasks().publish(snapshot);
                        }
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
    void publishingAnUnknownTaskNotifiesNoSessionEvenFromAHandler() throws Exception {
        try (var owner = createTestClient();
                var bystander = createTestClient()) {
            owner.initialize();
            bystander.initialize();
            var ownerStream = primedGetStream(owner.openGetStream(null));
            var bystanderStream = primedGetStream(bystander.openGetStream(null));

            var response = owner.sendRpc("""
                    {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                      "name":"publish-server-scoped","arguments":{}}}
                    """);
            assertThatJson(response.body()).inPath("$.result.content[0].text").isEqualTo("published");

            var fence = fence(ownerStream, bystanderStream);

            assertThat(notificationsBefore(fence, ownerStream, "notifications/tasks/status"))
                    .as("server.tasks() never infers an owner from the calling thread")
                    .isEmpty();
            assertThat(notificationsBefore(fence, bystanderStream, "notifications/tasks/status"))
                    .as("a task without an owning session must not be broadcast to other sessions")
                    .isEmpty();
            assertThat(server.tasks().get("orphan"))
                    .as("only a task-augmented tool call creates a cached task")
                    .isNull();
        }
    }

    @Test
    @Timeout(30)
    void asyncTaskToolNotifiesOnlyItsCaller() throws Exception {
        try (var owner = createTestClient();
                var bystander = createTestClient()) {
            owner.initialize();
            bystander.initialize();
            var ownerStream = primedGetStream(owner.openGetStream(null));
            var bystanderStream = primedGetStream(bystander.openGetStream(null));

            book(owner, "owned", false, "tok-owner");

            var status = awaitNotification(ownerStream, "notifications/tasks/status", "owned");
            assertThatJson(status.json().path("params").toString())
                    .isObject()
                    .containsEntry("taskId", "owned")
                    .containsEntry("status", "working");

            server.tasks().reportProgress("owned", 0.5, 1.0, "halfway");
            var progress = awaitNotification(ownerStream, "notifications/progress", "halfway");
            // language=JSON
            assertThatJson(progress.json().path("params").toString()).isEqualTo("""
                    {"progressToken":"tok-owner","progress":0.5,"total":1.0,"message":"halfway"}
                    """);

            var fence = fence(ownerStream, bystanderStream);

            assertThat(notificationsBefore(fence, bystanderStream, "notifications/tasks/status"))
                    .as("the caller's task status must not reach another session")
                    .isEmpty();
            assertThat(notificationsBefore(fence, bystanderStream, "notifications/progress"))
                    .as("the caller's task progress must not reach another session")
                    .isEmpty();
        }
    }

    @Test
    @Timeout(30)
    void taskPublishedBeforeItsCreateStillBelongsToTheCaller() throws Exception {
        var taskId = "published-early";
        try (var owner = createTestClient();
                var bystander = createTestClient()) {
            owner.initialize();
            bystander.initialize();
            var ownerStream = primedGetStream(owner.openGetStream(null));
            var bystanderStream = primedGetStream(bystander.openGetStream(null));

            book(owner, taskId, true, "tok-early");

            // The early publish is not cached, so the tool call creates the task with its session and token.
            server.tasks().reportProgress(taskId, 0.75, 1.0, "claimed");
            var progress = awaitNotification(ownerStream, "notifications/progress", "claimed");
            // language=JSON
            assertThatJson(progress.json().path("params").toString()).isEqualTo("""
                    {"progressToken":"tok-early","progress":0.75,"total":1.0,"message":"claimed"}
                    """);

            var fence = fence(ownerStream, bystanderStream);
            // A status sent while the tool runs rides its POST response; one sent at the handoff, the GET stream.
            var statusParams = Stream.concat(
                            owner.notifications().stream()
                                    .filter(n -> n.method().equals("notifications/tasks/status"))
                                    .map(n -> String.valueOf(n.params())),
                            notificationsBefore(fence, ownerStream, "notifications/tasks/status").stream()
                                    .map(f -> f.json().path("params").toString()))
                    .toList();
            assertThat(statusParams)
                    .as("the owner learns the task's status exactly once, from its creation")
                    .singleElement()
                    .satisfies(params -> assertThatJson(params)
                            .isObject()
                            .containsEntry("taskId", taskId)
                            .containsEntry("status", "working"));
            assertThat(notificationsBefore(fence, bystanderStream, "notifications/progress"))
                    .as("the task's progress must not reach another session")
                    .isEmpty();
        }
    }

    @Test
    @Timeout(30)
    void createNeverTakesATaskFromItsOwner() throws Exception {
        try (var owner = createTestClient();
                var intruder = createTestClient()) {
            owner.initialize();
            intruder.initialize();
            var ownerStream = primedGetStream(owner.openGetStream(null));
            var intruderStream = primedGetStream(intruder.openGetStream(null));
            book(owner, "contested", false, "tok-owner");

            // A higher revision would win the cache if the intruder's create were applied.
            var collision = intruder.sendRpc("""
                    {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
                      "name":"book-async","arguments":{"taskId":"contested","revision":2},"task":{},
                      "_meta":{"progressToken":"tok-intruder"}}}
                    """);
            assertThatJson(collision.body()).inPath("$.error.code").isEqualTo(-32603);
            assertThatJson(collision.body()).node("result").isAbsent();
            var cached = server.tasks().get("contested");
            assertThat(cached).as("the owner's cached task is unchanged").isNotNull();
            assertThat(cached.revision()).isEqualTo(1);

            server.tasks().reportProgress("contested", 0.5, 1.0, "still-mine");
            var progress = awaitNotification(ownerStream, "notifications/progress", "still-mine");
            // language=JSON
            assertThatJson(progress.json().path("params").toString()).isEqualTo("""
                    {"progressToken":"tok-owner","progress":0.5,"total":1.0,"message":"still-mine"}
                    """);

            var fence = fence(ownerStream, intruderStream);
            assertThat(notificationsBefore(fence, ownerStream, "notifications/tasks/status"))
                    .as("the owner gets its own task's status only, not the intruder's revision")
                    .hasSize(1);
            assertThat(notificationsBefore(fence, intruderStream, "notifications/tasks/status"))
                    .as("the intruder learns nothing about the owner's task")
                    .isEmpty();
            assertThat(notificationsBefore(fence, intruderStream, "notifications/progress"))
                    .as("a colliding task id must not redirect the owner's progress")
                    .isEmpty();
        }
    }

    @Test
    @Timeout(30)
    void otherSessionsCannotReachAnOwnedTask() throws Exception {
        try (var owner = createTestClient();
                var intruder = createTestClient()) {
            owner.initialize();
            intruder.initialize();
            var ownerStream = primedGetStream(owner.openGetStream(null));
            taskEngine.publish(TaskSnapshot.working("private-task", CREATED_AT, 1));
            book(owner, "private-task", false, "tok-owner");
            awaitNotification(ownerStream, "notifications/tasks/status", "private-task");

            // Same answer as an unknown id, so an intruder cannot tell the task exists.
            for (var method : List.of("tasks/get", "tasks/cancel", "tasks/result")) {
                var response = intruder.sendRpc("""
                        {"jsonrpc":"2.0","id":3,"method":"%s","params":{"taskId":"private-task"}}
                        """.formatted(method));
                assertThatResponse(response)
                        .as(method)
                        .isJsonRpcError()
                        .hasErrorCode(-32602)
                        .hasErrorMessageContaining("Task not found");
            }
            assertThat(taskEngine.cancelledTaskIds())
                    .as("the connector never sees the intruder's cancel")
                    .doesNotContain("private-task");
            assertThat(taskEngine.awaitedTaskIds()).doesNotContain("private-task");
            assertThat(taskEngine.refreshedTaskIds()).doesNotContain("private-task");

            var intruderList = intruder.sendRpc("""
                    {"jsonrpc":"2.0","id":4,"method":"tasks/list","params":{}}
                    """);
            assertThatJson(intruderList.body())
                    .inPath("$.result.tasks[*].taskId")
                    .isArray()
                    .doesNotContain("private-task");
            var ownerList = owner.sendRpc("""
                    {"jsonrpc":"2.0","id":4,"method":"tasks/list","params":{}}
                    """);
            assertThatJson(ownerList.body())
                    .inPath("$.result.tasks[*].taskId")
                    .isArray()
                    .contains("private-task");

            taskEngine.publish(TaskSnapshot.builder()
                    .from(TaskSnapshot.working("private-task", CREATED_AT, 2))
                    .statusMessage("refreshed")
                    .build());
            var get = owner.sendRpc("""
                    {"jsonrpc":"2.0","id":5,"method":"tasks/get","params":{"taskId":"private-task"}}
                    """);
            assertThatJson(get.body()).inPath("$.result.statusMessage").isEqualTo("refreshed");
            // The owner's own tasks/get carries the refreshed status on its POST response.
            owner.awaitNotification(
                    "notifications/tasks/status",
                    params -> "refreshed".equals(params.path("statusMessage").asString()),
                    ofSeconds(5));
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"tasks/get", "tasks/cancel", "tasks/result"})
    @Timeout(30)
    void readingAnUncachedTaskNeverTakesItFromItsCreator(String method) throws Exception {
        var taskId = "read-first-" + method.substring("tasks/".length());
        try (var owner = createTestClient();
                var reader = createTestClient()) {
            owner.initialize();
            reader.initialize();
            var ownerStream = primedGetStream(owner.openGetStream(null));
            var readerStream = primedGetStream(reader.openGetStream(null));
            taskEngine.publish(TaskSnapshot.working(taskId, CREATED_AT, 1));

            reader.sendRpc("""
                    {"jsonrpc":"2.0","id":2,"method":"%s","params":{"taskId":"%s"}}
                    """.formatted(method, taskId));
            assertThat(server.tasks().get(taskId))
                    .as("a read never caches a task")
                    .isNull();

            book(owner, taskId, false, "tok-owner");

            server.tasks().reportProgress(taskId, 0.5, 1.0, "creator-owns");
            var progress = awaitNotification(ownerStream, "notifications/progress", "creator-owns");
            // language=JSON
            assertThatJson(progress.json().path("params").toString()).isEqualTo("""
                    {"progressToken":"tok-owner","progress":0.5,"total":1.0,"message":"creator-owns"}
                    """);

            var fence = fence(ownerStream, readerStream);
            assertThat(reader.notifications())
                    .as("a read must not subscribe the reader to the task's status")
                    .noneMatch(n -> n.method().equals("notifications/tasks/status"));
            assertThat(notificationsBefore(fence, readerStream, "notifications/tasks/status"))
                    .isEmpty();
            assertThat(notificationsBefore(fence, readerStream, "notifications/progress"))
                    .as("the creator's progress must not reach the reader")
                    .isEmpty();
        }
    }

    private static void book(Mcp20251125Client client, String taskId, boolean publishFirst, String progressToken)
            throws Exception {
        var response = client.sendRpc("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                  "name":"book-async","arguments":{"taskId":"%s","publishFirst":%s},"task":{},
                  "_meta":{"progressToken":"%s"}}}
                """.formatted(taskId, publishFirst, progressToken));
        assertThatJson(response.body()).inPath("$.result.task.taskId").isEqualTo(taskId);
    }

    private static SseStream primedGetStream(SseStream stream) {
        stream.awaitFirstEventId(ofSeconds(5));
        return stream;
    }

    private static SseFrame awaitNotification(SseStream stream, String method, String marker) {
        return stream.await(f -> isNotification(f, method) && f.data().contains(marker), ofSeconds(5));
    }

    /**
     * Broadcasts a log message to every session and waits until each stream delivers it. A leaked
     * notification sent earlier on the same stream arrives before the fence.
     */
    private String fence(SseStream... streams) {
        var marker = "fence-" + fences.incrementAndGet();
        server.notifications().error("tachyon.test", Map.of("event", marker));
        for (var stream : streams) {
            stream.await(f -> f.data().contains(marker), ofSeconds(5));
        }
        return marker;
    }

    private static List<SseFrame> notificationsBefore(String fence, SseStream stream, String method) {
        var frames = stream.received(f -> true);
        var fenceIndex = frames.stream()
                .filter(f -> f.data().contains(fence))
                .findFirst()
                .map(frames::indexOf)
                .orElseThrow();
        return frames.subList(0, fenceIndex).stream()
                .filter(f -> isNotification(f, method))
                .toList();
    }

    private static boolean isNotification(SseFrame frame, String method) {
        return !frame.data().isBlank()
                && method.equals(frame.json().path("method").asString());
    }
}
