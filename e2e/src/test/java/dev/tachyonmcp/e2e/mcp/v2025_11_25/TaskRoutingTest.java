/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2025_11_25;

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
 * Task push traffic follows the task-augmented tool call that returned the task: its session gets
 * {@code notifications/tasks/status} and its progress token gets {@code notifications/progress}, and
 * no other session does. The route is delivery, not access: {@link TaskConnectorAccessTest} covers who
 * reaches a task. The server is shared by the class, so every test uses task ids of its own.
 */
class TaskRoutingTest extends AbstractStatefulMcpE2eTest {

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
    void publishingAnUnknownTaskCachesItButNotifiesNoSession() throws Exception {
        try (var caller = createTestClient();
                var bystander = createTestClient()) {
            caller.initialize();
            bystander.initialize();
            var callerStream = primedGetStream(caller.openGetStream(null));
            var bystanderStream = primedGetStream(bystander.openGetStream(null));

            var response = caller.sendRpc("""
                    {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                      "name":"publish-server-scoped","arguments":{}}}
                    """);
            assertThatJson(response.body()).inPath("$.result.content[0].text").isEqualTo("published");

            var fence = fence(callerStream, bystanderStream);

            assertThat(notificationsBefore(fence, callerStream, "notifications/tasks/status"))
                    .as("server.tasks() never infers a route from the calling thread")
                    .isEmpty();
            assertThat(notificationsBefore(fence, bystanderStream, "notifications/tasks/status"))
                    .as("an unrouted task must not be broadcast to other sessions")
                    .isEmpty();
            assertThat(server.tasks().get("orphan"))
                    .as("publish caches a task Tachyon has not seen")
                    .isNotNull();
        }
    }

    @Test
    @Timeout(30)
    void asyncTaskToolNotifiesOnlyItsCaller() throws Exception {
        try (var caller = createTestClient();
                var bystander = createTestClient()) {
            caller.initialize();
            bystander.initialize();
            var callerStream = primedGetStream(caller.openGetStream(null));
            var bystanderStream = primedGetStream(bystander.openGetStream(null));

            book(caller, "routed", false, "tok-caller");

            var status = awaitNotification(callerStream, "notifications/tasks/status", "routed");
            assertThatJson(status.json().path("params").toString())
                    .isObject()
                    .containsEntry("taskId", "routed")
                    .containsEntry("status", "working");

            server.tasks().reportProgress("routed", 0.5, 1.0, "halfway");
            var progress = awaitNotification(callerStream, "notifications/progress", "halfway");
            // language=JSON
            assertThatJson(progress.json().path("params").toString()).isEqualTo("""
                    {"progressToken":"tok-caller","progress":0.5,"total":1.0,"message":"halfway"}
                    """);

            var fence = fence(callerStream, bystanderStream);

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
    void taskPublishedBeforeItsCreateIsRoutedToTheCaller() throws Exception {
        var taskId = "published-early";
        try (var caller = createTestClient();
                var bystander = createTestClient()) {
            caller.initialize();
            bystander.initialize();
            var callerStream = primedGetStream(caller.openGetStream(null));
            var bystanderStream = primedGetStream(bystander.openGetStream(null));

            book(caller, taskId, true, "tok-early");

            // The early publish cached the task unrouted; the tool call's result gave it a route.
            server.tasks().reportProgress(taskId, 0.75, 1.0, "routed-late");
            var progress = awaitNotification(callerStream, "notifications/progress", "routed-late");
            // language=JSON
            assertThatJson(progress.json().path("params").toString()).isEqualTo("""
                    {"progressToken":"tok-early","progress":0.75,"total":1.0,"message":"routed-late"}
                    """);
            server.tasks()
                    .publish(TaskSnapshot.builder()
                            .from(TaskSnapshot.working(taskId, CREATED_AT, 2))
                            .statusMessage("later")
                            .build());
            awaitNotification(callerStream, "notifications/tasks/status", "later");

            var fence = fence(callerStream, bystanderStream);
            // A status sent while the tool runs rides its POST response; later ones, the GET stream.
            var statusParams = Stream.concat(
                            caller.notifications().stream()
                                    .filter(n -> n.method().equals("notifications/tasks/status"))
                                    .map(n -> String.valueOf(n.params())),
                            notificationsBefore(fence, callerStream, "notifications/tasks/status").stream()
                                    .map(f -> f.json().path("params").toString()))
                    .toList();
            assertThat(statusParams)
                    .as("revision 1 was published unrouted and came back in the tool result;"
                            + " only the later revision is pushed")
                    .singleElement()
                    .satisfies(params -> assertThatJson(params)
                            .isObject()
                            .containsEntry("taskId", taskId)
                            .containsEntry("statusMessage", "later"));
            assertThat(notificationsBefore(fence, bystanderStream, "notifications/tasks/status"))
                    .isEmpty();
            assertThat(notificationsBefore(fence, bystanderStream, "notifications/progress"))
                    .as("the task's progress must not reach another session")
                    .isEmpty();
        }
    }

    @Test
    @Timeout(30)
    void aCollidingToolCallNeverReroutesTheTask() throws Exception {
        try (var first = createTestClient();
                var second = createTestClient()) {
            first.initialize();
            second.initialize();
            var firstStream = primedGetStream(first.openGetStream(null));
            var secondStream = primedGetStream(second.openGetStream(null));
            book(first, "contested", false, "tok-first");
            awaitNotification(firstStream, "notifications/tasks/status", "contested");

            var collision = second.sendRpc("""
                    {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
                      "name":"book-async","arguments":{"taskId":"contested","revision":2},"task":{},
                      "_meta":{"progressToken":"tok-second"}}}
                    """);
            assertThatJson(collision.body())
                    .as("the call is never refused: access is the connector's decision")
                    .inPath("$.result.task.taskId")
                    .isEqualTo("contested");
            var cached = server.tasks().get("contested");
            assertThat(cached).isNotNull();
            assertThat(cached.revision())
                    .as("the newer revision wins the cache")
                    .isEqualTo(2);

            server.tasks().reportProgress("contested", 0.5, 1.0, "still-first");
            var progress = awaitNotification(firstStream, "notifications/progress", "still-first");
            // language=JSON
            assertThatJson(progress.json().path("params").toString()).isEqualTo("""
                    {"progressToken":"tok-first","progress":0.5,"total":1.0,"message":"still-first"}
                    """);

            var fence = fence(firstStream, secondStream);
            assertThat(notificationsBefore(fence, firstStream, "notifications/tasks/status"))
                    .as("the first route keeps the task's status, the newer revision included")
                    .hasSize(2);
            assertThat(second.notifications()).noneMatch(n -> n.method().equals("notifications/tasks/status"));
            assertThat(notificationsBefore(fence, secondStream, "notifications/tasks/status"))
                    .isEmpty();
            assertThat(notificationsBefore(fence, secondStream, "notifications/progress"))
                    .as("a colliding task id must not redirect the first caller's progress")
                    .isEmpty();
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"tasks/get", "tasks/cancel", "tasks/result"})
    @Timeout(30)
    void aReadNeverRoutesATask(String method) throws Exception {
        var taskId = "read-first-" + method.substring("tasks/".length());
        try (var booker = createTestClient();
                var reader = createTestClient()) {
            booker.initialize();
            reader.initialize();
            var bookerStream = primedGetStream(booker.openGetStream(null));
            var readerStream = primedGetStream(reader.openGetStream(null));
            taskEngine.publish(TaskSnapshot.working(taskId, CREATED_AT, 1));

            reader.sendRpc("""
                    {"jsonrpc":"2.0","id":2,"method":"%s","params":{"taskId":"%s"}}
                    """.formatted(method, taskId));
            assertThat(server.tasks().get(taskId))
                    .as("a read caches the connector's snapshot")
                    .isNotNull();

            book(booker, taskId, false, "tok-booker");

            server.tasks().reportProgress(taskId, 0.5, 1.0, "booker-route");
            var progress = awaitNotification(bookerStream, "notifications/progress", "booker-route");
            // language=JSON
            assertThatJson(progress.json().path("params").toString()).isEqualTo("""
                    {"progressToken":"tok-booker","progress":0.5,"total":1.0,"message":"booker-route"}
                    """);

            var fence = fence(bookerStream, readerStream);
            assertThat(reader.notifications())
                    .as("a read must not route the task's status to the reader")
                    .noneMatch(n -> n.method().equals("notifications/tasks/status"));
            assertThat(notificationsBefore(fence, readerStream, "notifications/tasks/status"))
                    .isEmpty();
            assertThat(notificationsBefore(fence, readerStream, "notifications/progress"))
                    .as("the booker's progress must not reach the reader")
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
