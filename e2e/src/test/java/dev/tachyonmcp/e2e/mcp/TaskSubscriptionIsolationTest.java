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

class TaskSubscriptionIsolationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-09-25T07:00:00Z");

    @Test
    @Timeout(30)
    void sessionOwnedTasksStayPrivateAcrossProtocolVersionsAndSessionTermination() throws Exception {
        final var connector = new TestTaskConnector().connector();
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
            try (final var owner = McpTestClients.forVersion(server.port(), "2025-11-25");
                    final var subscriber = McpTestClients.latest(server.port())
                            .withExtensions(Map.of(TasksExtension.ID, JsonUtils.parseJsonNode("{}")))) {
                final var sessionId = owner.initialize();
                assertThat(sessionId).isNotNull();
                final var ownerStream = owner.openGetStream(null);
                ownerStream.awaitFirstEventId(ofSeconds(5));
                final var earlySubscriber = listen(subscriber, 1);

                // language=JSON
                final var created = owner.sendRpc("""
                        {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                          "name":"start-task","arguments":{"taskId":"private-task"},"task":{}}}
                        """);
                // language=JSON
                assertThat(created).isSuccess().hasId(2).hasResult("""
                        {"task":{"taskId":"private-task","status":"working",
                          "createdAt":"2026-09-25T07:00:00Z","lastUpdatedAt":"2026-09-25T07:00:00Z",
                          "ttl":null}}
                        """);
                final var lateSubscriber = listen(subscriber, 2);

                // Same answer as an unknown id, so a subscriber cannot tell the private task exists.
                for (final var request : List.of(
                        "\"method\":\"tasks/get\",\"params\":{\"taskId\":\"%s\"}",
                        "\"method\":\"tasks/cancel\",\"params\":{\"taskId\":\"%s\"}",
                        "\"method\":\"tasks/update\",\"params\":{\"taskId\":\"%s\",\"inputResponses\":{}}")) {
                    final var denied =
                            subscriber.post("{\"jsonrpc\":\"2.0\",\"id\":3," + request.formatted("private-task") + "}");
                    final var unknown = subscriber.post(
                            "{\"jsonrpc\":\"2.0\",\"id\":3," + request.formatted("never-created") + "}");
                    assertThat(denied)
                            .isJsonRpcError()
                            .hasId(3)
                            .hasErrorCode(-32602)
                            .hasErrorMessageContaining("Task not found");
                    assertThat(denied.statusCode()).isEqualTo(unknown.statusCode());
                    assertThatJson(denied.body())
                            .as("denied and unknown ids must be indistinguishable: %s", request)
                            .isEqualTo(unknown.body());
                }

                server.tasks()
                        .publish(TaskSnapshot.completed(
                                "private-task", CREATED_AT, CREATED_AT, 2, ToolResult.text("private result")));
                final var completion = ownerStream.await(
                        f -> isTaskNotification(f, "notifications/tasks/status", "private-task")
                                && "completed"
                                        .equals(f.json()
                                                .path("params")
                                                .path("status")
                                                .asString()),
                        ofSeconds(5));
                // language=JSON
                assertThatJson(completion.data()).isEqualTo("""
                        {"jsonrpc":"2.0","method":"notifications/tasks/status","params":{
                          "taskId":"private-task","status":"completed",
                          "createdAt":"2026-09-25T07:00:00Z","lastUpdatedAt":"2026-09-25T07:00:00Z",
                          "ttl":null}}
                        """);

                assertThat(owner.delete(sessionId).statusCode()).isEqualTo(200);
                server.tasks()
                        .publish(TaskSnapshot.completed(
                                "private-task",
                                CREATED_AT,
                                CREATED_AT,
                                3,
                                ToolResult.text("private result after disconnect")));

                // language=JSON
                final var publicTask = subscriber.sendRpc("""
                        {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{
                          "name":"start-task","arguments":{"taskId":"public-task"}}}
                        """);
                assertThat(publicTask).isSuccess().hasId(4).hasResultType("task");
                server.tasks()
                        .publish(TaskSnapshot.completed(
                                "public-task", CREATED_AT, CREATED_AT, 2, ToolResult.text("public result")));

                for (final var stream : List.of(earlySubscriber, lateSubscriber)) {
                    // This later event fences preceding writes on the same stream; no timed absence check.
                    final var publicCompletion = stream.await(
                            f -> isTaskNotification(f, "notifications/tasks", "public-task")
                                    && "completed"
                                            .equals(f.json()
                                                    .path("params")
                                                    .path("status")
                                                    .asString()),
                            ofSeconds(5));
                    // language=JSON
                    assertThatJson(publicCompletion.data()).isEqualTo("""
                            {"jsonrpc":"2.0","method":"notifications/tasks","params":{
                              "taskId":"public-task","status":"completed",
                              "createdAt":"2026-09-25T07:00:00Z","lastUpdatedAt":"2026-09-25T07:00:00Z",
                              "ttlMs":null,"result":{
                                "content":[{"type":"text","text":"public result"}],"resultType":"complete"}}}
                            """);
                    assertThat(stream.received(f -> isTaskNotification(f, "notifications/tasks", "private-task")))
                            .as("session-owned tasks stay private before and after their owner disconnects")
                            .isEmpty();
                }
            }
        }
    }

    private static SseStream listen(Mcp20260728Client client, int id) throws Exception {
        // language=JSON
        final var stream = client.openPostStream(null, """
                {"jsonrpc":"2.0","id":%d,"method":"subscriptions/listen",
                  "params":{"notifications":{"taskIds":["private-task","public-task"]}}}
                """.formatted(id));
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
