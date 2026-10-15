/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2026_07_28;

import static dev.tachyonmcp.e2e.mcp.OwnedTasksFixture.CREATED_AT;
import static dev.tachyonmcp.e2e.mcp.TasksSupport.tasks;
import static dev.tachyonmcp.e2e.mcp.TestBearerTokens.ALICE;
import static dev.tachyonmcp.e2e.mcp.TestBearerTokens.BOB;
import static dev.tachyonmcp.testkit.JsonRpcResponseAssert.assertThat;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.e2e.mcp.AbstractStatelessMcpE2eTest;
import dev.tachyonmcp.e2e.mcp.OwnedTasksFixture;
import dev.tachyonmcp.extensions.tasks.TasksExtension;
import dev.tachyonmcp.testkit.Mcp20260728Client;
import dev.tachyonmcp.testkit.McpClient;
import dev.tachyonmcp.testkit.McpTestClients;
import dev.tachyonmcp.testkit.SseFrame;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * ext-tasks 2026-07-28 § Security: authenticate and authorize every task-related request; a task id
 * may act as a bearer token. The task a caller's tool call creates belongs to that caller. Anyone
 * else is told the task does not exist, exactly as for an unknown id, before the connector is
 * asked, and a {@code subscriptions/listen} stream never follows it.
 */
@Timeout(30)
class TaskOwnerBindingTest extends AbstractStatelessMcpE2eTest<McpClient> {

    private OwnedTasksFixture fixture = new OwnedTasksFixture();

    @Override
    protected Mcp20260728Client createTestClient() {
        return createTestClient(port);
    }

    @Override
    protected Mcp20260728Client createTestClient(int port) {
        return McpTestClients.latest(port);
    }

    @BeforeEach
    void startSecuredTasksServer() {
        fixture = new OwnedTasksFixture();
        startServer(fixture::configure, fixture::register);
    }

    private Mcp20260728Client signedIn(String token) {
        return new Mcp20260728Client(port, token)
                .withExtensions(Map.of(TasksExtension.ID, JsonNodeFactory.instance.objectNode()));
    }

    private static String start(int id, String taskId) {
        // language=JSON
        return """
                {"jsonrpc":"2.0","id":%d,"method":"tools/call","params":{
                  "name":"start","arguments":{"taskId":"%s"}}}
                """.formatted(id, taskId);
    }

    @Test
    void anotherCallerIsToldTheTaskDoesNotExistOnEveryTaskMethod() throws Exception {
        var taskId = UUID.randomUUID().toString();
        var unknownId = UUID.randomUUID().toString();
        try (var alice = signedIn(ALICE);
                var bob = signedIn(BOB)) {
            assertThat(alice.post(start(1, taskId))).isSuccess().hasResultType("task");

            for (var request : List.of("""
                    {"jsonrpc":"2.0","id":2,"method":"tasks/get","params":{"taskId":"%s"}}
                    """, """
                    {"jsonrpc":"2.0","id":2,"method":"tasks/cancel","params":{"taskId":"%s"}}
                    """, """
                    {"jsonrpc":"2.0","id":2,"method":"tasks/update","params":{"taskId":"%s","inputResponses":{}}}
                    """)) {
                var denied = bob.post(request.formatted(taskId));
                var unknown = bob.post(request.formatted(unknownId));

                assertThat(denied).isJsonRpcError().hasErrorCode(-32602);
                assertThat(denied.body())
                        .as("the refusal reads like an unknown id: %s", request)
                        .isEqualTo(unknown.body());
            }

            assertThat(fixture.connectorCalls())
                    .as("the connector is never asked about a task the caller does not own")
                    .noneMatch(call -> call.contains(":" + taskId + ":bob"));
            assertThat(alice.post("""
                    {"jsonrpc":"2.0","id":3,"method":"tasks/get","params":{"taskId":"%s"}}
                    """.formatted(taskId))).isSuccess().hasId(3);
        }
    }

    @Test
    void aListenStreamAcknowledgesOnlyTheTasksItsCallerMayRead() throws Exception {
        var aliceTask = UUID.randomUUID().toString();
        var bobTask = UUID.randomUUID().toString();
        try (var alice = signedIn(ALICE);
                var bob = signedIn(BOB)) {
            alice.post(start(1, aliceTask));
            bob.post(start(1, bobTask));

            try (var stream = bob.openPostStream(null, """
                    {"jsonrpc":"2.0","id":4,"method":"subscriptions/listen",
                     "params":{"notifications":{"taskIds":["%s","%s"]}}}
                    """.formatted(aliceTask, bobTask))) {
                var acknowledged = stream.await(
                        frame -> frame.data().contains("notifications/subscriptions/acknowledged"), ofSeconds(5));
                tasks(server).publish(TaskSnapshot.working(aliceTask, CREATED_AT, 2));
                tasks(server).publish(TaskSnapshot.working(bobTask, CREATED_AT, 2));
                stream.await(frame -> isTaskNotification(frame, bobTask), ofSeconds(5));

                assertThat(acknowledged.data()).contains(bobTask).doesNotContain(aliceTask);
                assertThat(stream.received(frame -> isTaskNotification(frame, aliceTask)))
                        .as("a stream never follows a task its caller does not own")
                        .isEmpty();
            }
        }
    }

    @Test
    void aStreamOpenedBeforeTheTaskWasOwnedNeverFollowsItAfterwards() throws Exception {
        var contested = UUID.randomUUID().toString();
        var readable = UUID.randomUUID().toString();
        fixture.startExternally(TaskSnapshot.working(contested, CREATED_AT, 1));
        fixture.startExternally(TaskSnapshot.working(readable, CREATED_AT, 1));
        try (var alice = signedIn(ALICE);
                var bob = signedIn(BOB);
                var bobStream = bob.openPostStream(null, listen(contested, readable));
                var aliceStream = alice.openPostStream(null, listen(contested))) {
            var bobAck = bobStream.await(TaskOwnerBindingTest::isAcknowledgement, ofSeconds(5));
            aliceStream.await(TaskOwnerBindingTest::isAcknowledgement, ofSeconds(5));
            assertThat(bobAck.data())
                    .as("nobody owns the task yet, so the open-time check lets bob follow it")
                    .contains(contested)
                    .contains(readable);

            assertThat(alice.post(start(1, contested))).isSuccess().hasResultType("task");
            aliceStream.await(frame -> isTaskNotification(frame, contested), ofSeconds(5));
            tasks(server).publish(TaskSnapshot.working(readable, CREATED_AT, 2));
            bobStream.await(frame -> isTaskNotification(frame, readable), ofSeconds(5));

            assertThat(bobStream.received(frame -> isTaskNotification(frame, contested)))
                    .as("once alice owns the task, bob's stream stops carrying it")
                    .isEmpty();
        }
    }

    @Test
    void aReadBeforeTheCreatorsToolReturnsDoesNotMakeTheTaskUnowned() throws Exception {
        var taskId = UUID.randomUUID().toString();
        fixture.startExternally(TaskSnapshot.working(taskId, CREATED_AT, 1));
        try (var alice = signedIn(ALICE);
                var bob = signedIn(BOB)) {
            assertThat(bob.post(get(2, taskId))).isSuccess().hasId(2);

            assertThat(alice.post(start(1, taskId))).isSuccess().hasResultType("task");

            assertThat(bob.post(get(3, taskId))).isJsonRpcError().hasErrorCode(-32602);
            assertThat(alice.post(get(4, taskId))).isSuccess().hasId(4);
        }
    }

    private static String get(int id, String taskId) {
        // language=JSON
        return """
                {"jsonrpc":"2.0","id":%d,"method":"tasks/get","params":{"taskId":"%s"}}
                """.formatted(id, taskId);
    }

    private static String listen(String... taskIds) {
        var quoted =
                String.join(",", Stream.of(taskIds).map(id -> "\"" + id + "\"").toList());
        // language=JSON
        return """
                {"jsonrpc":"2.0","id":4,"method":"subscriptions/listen",
                 "params":{"notifications":{"taskIds":[%s]}}}
                """.formatted(quoted);
    }

    private static boolean isAcknowledgement(SseFrame frame) {
        return frame.data().contains("notifications/subscriptions/acknowledged");
    }

    private static boolean isTaskNotification(SseFrame frame, String taskId) {
        if (frame.data().isBlank()) return false;
        var json = frame.json();
        return "notifications/tasks".equals(json.path("method").asString())
                && taskId.equals(json.path("params").path("taskId").asString());
    }
}
