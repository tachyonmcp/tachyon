/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp;

import static dev.tachyonmcp.testkit.JsonRpcResponseAssert.assertThat;
import static java.time.Duration.ofSeconds;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.api.server.features.tasks.TaskSupport;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.server.features.tasks.TasksExtension;
import dev.tachyonmcp.core.server.json.JsonUtils;
import dev.tachyonmcp.testkit.Mcp20260728Client;
import dev.tachyonmcp.testkit.McpTestClients;
import dev.tachyonmcp.testkit.SseFrame;
import dev.tachyonmcp.testkit.SseStream;
import dev.tachyonmcp.testkit.TestTaskConnector;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A task's status goes to its route session and to every {@code subscriptions/listen} subscriber the
 * task connector let read it, across protocol versions. The connector authorizes each listened id
 * when the stream opens (ext-tasks 2026-07-28 § Security), so delivery does not depend on whether the
 * task is still routed or cached. A subscriber never receives a task it did not name.
 */
class TaskSubscriptionRoutingTest {

    private static final Instant CREATED_AT = Instant.parse("2026-09-25T07:00:00Z");

    @Test
    @Timeout(30)
    void taskReachesItsRouteSessionAndEveryAuthorizedListenerNamingIt() throws Exception {
        final var taskEngine = new TestTaskConnector()
                .publish(TaskSnapshot.working("routed-task", CREATED_AT, 1))
                .publish(TaskSnapshot.working("unrouted-task", CREATED_AT, 1));
        final var connector = taskEngine.connector();
        try (final var server = TachyonServer.builder()
                .port(0)
                .session(s -> s.enabled())
                .capabilities(c -> c.tasks(connector))
                .withTools(tools -> tools.register(
                        b -> b.name("start-task").taskSupport(TaskSupport.REQUIRED),
                        (context, request) -> ToolResult.task(
                                TaskSnapshot.working(request.arguments().stringOr("taskId", ""), CREATED_AT, 1))))
                .build()) {
            server.start();
            try (final var creator = McpTestClients.forVersion(server.port(), "2025-11-25");
                    final var subscriber = McpTestClients.latest(server.port())
                            .withExtensions(Map.of(TasksExtension.ID, JsonUtils.parseJsonNode("{}")))) {
                final var sessionId = creator.initialize();
                assertThat(sessionId).isNotNull();
                final var creatorStream = creator.openGetStream(null);
                creatorStream.awaitFirstEventId(ofSeconds(5));
                final var earlySubscriber = listen(subscriber, 1, "routed-task", "unrouted-task");
                final var otherSubscriber = listen(subscriber, 5, "unrouted-task");

                // language=JSON
                final var created = creator.sendRpc("""
                        {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                          "name":"start-task","arguments":{"taskId":"routed-task"},"task":{}}}
                        """);
                // language=JSON
                assertThat(created).isSuccess().hasId(2).hasResult("""
                        {"task":{"taskId":"routed-task","status":"working",
                          "createdAt":"2026-09-25T07:00:00Z","lastUpdatedAt":"2026-09-25T07:00:00Z",
                          "ttl":null}}
                        """);
                final var lateSubscriber = listen(subscriber, 3, "routed-task", "unrouted-task");

                server.tasks()
                        .publish(TaskSnapshot.completed(
                                "routed-task", CREATED_AT, CREATED_AT, 2, ToolResult.text("routed result")));
                final var completion = creatorStream.await(
                        f -> isTaskNotification(f, "notifications/tasks/status", "routed-task")
                                && "completed"
                                        .equals(f.json()
                                                .path("params")
                                                .path("status")
                                                .asString()),
                        ofSeconds(5));
                // language=JSON
                assertThatJson(completion.data()).isEqualTo("""
                        {"jsonrpc":"2.0","method":"notifications/tasks/status","params":{
                          "taskId":"routed-task","status":"completed",
                          "createdAt":"2026-09-25T07:00:00Z","lastUpdatedAt":"2026-09-25T07:00:00Z",
                          "ttl":null}}
                        """);

                assertThat(creator.delete(sessionId).statusCode()).isEqualTo(200);
                server.tasks()
                        .publish(TaskSnapshot.completed(
                                "routed-task",
                                CREATED_AT,
                                CREATED_AT,
                                3,
                                ToolResult.text("routed result after disconnect")));

                // language=JSON
                final var unroutedTask = subscriber.sendRpc("""
                        {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{
                          "name":"start-task","arguments":{"taskId":"unrouted-task"}}}
                        """);
                assertThat(unroutedTask).isSuccess().hasId(4).hasResultType("task");
                server.tasks()
                        .publish(TaskSnapshot.completed(
                                "unrouted-task", CREATED_AT, CREATED_AT, 2, ToolResult.text("unrouted result")));

                for (final var stream : List.of(earlySubscriber, lateSubscriber)) {
                    for (final var expected : List.of("routed result", "routed result after disconnect")) {
                        final var routed = stream.await(
                                f -> isTaskNotification(f, "notifications/tasks", "routed-task")
                                        && f.data().contains("\"" + expected + "\""),
                                ofSeconds(5));
                        // language=JSON
                        assertThatJson(routed.data()).isEqualTo("""
                                {"jsonrpc":"2.0","method":"notifications/tasks","params":{
                                  "taskId":"routed-task","status":"completed",
                                  "createdAt":"2026-09-25T07:00:00Z","lastUpdatedAt":"2026-09-25T07:00:00Z",
                                  "ttlMs":null,"result":{
                                    "content":[{"type":"text","text":"%s"}],"resultType":"complete"}}}
                                """.formatted(expected));
                    }
                }
                for (final var stream : List.of(earlySubscriber, lateSubscriber, otherSubscriber)) {
                    // language=JSON
                    assertThatJson(stream.await(
                                            f -> isTaskNotification(f, "notifications/tasks", "unrouted-task")
                                                    && f.data().contains("\"unrouted result\""),
                                            ofSeconds(5))
                                    .data())
                            .isEqualTo("""
                                    {"jsonrpc":"2.0","method":"notifications/tasks","params":{
                                      "taskId":"unrouted-task","status":"completed",
                                      "createdAt":"2026-09-25T07:00:00Z","lastUpdatedAt":"2026-09-25T07:00:00Z",
                                      "ttlMs":null,"result":{
                                        "content":[{"type":"text","text":"unrouted result"}],"resultType":"complete"}}}
                                    """);
                }
                // The unrouted completion above was published last, so it fences earlier writes.
                assertThat(otherSubscriber.received(f -> isTaskNotification(f, "notifications/tasks", "routed-task")))
                        .as("a subscriber never receives a task it did not name")
                        .isEmpty();
            }
        }
    }

    private static SseStream listen(Mcp20260728Client client, int id, String... taskIds) throws Exception {
        final var ids = String.join("\",\"", taskIds);
        // language=JSON
        final var stream = client.openPostStream(null, """
                {"jsonrpc":"2.0","id":%d,"method":"subscriptions/listen",
                  "params":{"notifications":{"taskIds":["%s"]}}}
                """.formatted(id, ids));
        stream.await(f -> f.data().contains("notifications/subscriptions/acknowledged"), ofSeconds(5));
        return stream;
    }

    private static boolean isTaskNotification(SseFrame frame, String method, String taskId) {
        if (frame.data().isBlank()) return false;
        final var json = frame.json();
        return method.equals(json.path("method").asString())
                && taskId.equals(json.path("params").path("taskId").asString());
    }
}
