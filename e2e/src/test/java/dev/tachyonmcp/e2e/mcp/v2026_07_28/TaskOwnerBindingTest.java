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

    private Mcp20260728Client anonymous() {
        return new Mcp20260728Client(port)
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

    /**
     * The owner travels with the stored task, so a server that has never seen it (restarted, or
     * another node on the same connector) still refuses everyone but its owner, listen included.
     */
    @Test
    void aRestartedServerStillHidesTheTaskFromAnotherCaller() throws Exception {
        var taskId = UUID.randomUUID().toString();
        var unknownId = UUID.randomUUID().toString();
        try (var alice = signedIn(ALICE)) {
            assertThat(alice.post(start(1, taskId))).isSuccess().hasResultType("task");
        }

        stopServer();
        startServer(fixture::configure, fixture::register);

        try (var alice = signedIn(ALICE);
                var bob = signedIn(BOB)) {
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
                    .as("bob's cancel and update never ran")
                    .noneMatch(call -> call.equals("cancel:" + taskId + ":bob"))
                    .noneMatch(call -> call.equals("update:" + taskId + ":bob"));
            var bobTask = UUID.randomUUID().toString();
            assertThat(bob.post(start(4, bobTask))).isSuccess().hasResultType("task");
            try (var stream = bob.openPostStream(null, listen(taskId, bobTask))) {
                var acknowledged = stream.await(TaskOwnerBindingTest::isAcknowledgement, ofSeconds(5));
                assertThat(acknowledged.data())
                        .as("bob's listen follows his own task, not alice's")
                        .contains(bobTask)
                        .doesNotContain(taskId);
            }

            var aliceGet = alice.post(get(3, taskId));
            assertThat(aliceGet).isSuccess().hasId(3);
            assertThat(aliceGet.body()).as("the owner never goes on the wire").doesNotContain("alice");
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

    /**
     * A snapshot published without an owner, for a task this server no longer caches, names nobody.
     * The stream was authorized when it opened, so its owner keeps following the task even on a
     * server that hides unowned tasks from named callers.
     */
    @Test
    void theOwnersStreamStillFollowsATaskTheApplicationPublishesWithoutAnOwner() throws Exception {
        stopServer();
        fixture = new OwnedTasksFixture(false);
        startServer(fixture::configure, fixture::register);
        var aliceTask = UUID.randomUUID().toString();
        var bobTask = UUID.randomUUID().toString();
        try (var alice = signedIn(ALICE);
                var bob = signedIn(BOB)) {
            assertThat(alice.post(start(1, aliceTask))).isSuccess().hasResultType("task");
            assertThat(bob.post(start(1, bobTask))).isSuccess().hasResultType("task");
            try (var aliceStream = alice.openPostStream(null, listen(aliceTask));
                    var bobStream = bob.openPostStream(null, listen(aliceTask, bobTask))) {
                var aliceAck = aliceStream.await(TaskOwnerBindingTest::isAcknowledgement, ofSeconds(5));
                var bobAck = bobStream.await(TaskOwnerBindingTest::isAcknowledgement, ofSeconds(5));
                assertThat(aliceAck.data()).contains(aliceTask);
                assertThat(bobAck.data()).contains(bobTask).doesNotContain(aliceTask);

                assertThat(tasks(server).remove(aliceTask)).isTrue();
                assertThat(tasks(server).remove(bobTask)).isTrue();
                var republished = tasks(server).publish(TaskSnapshot.working(aliceTask, CREATED_AT, 2));
                tasks(server).publish(TaskSnapshot.working(bobTask, CREATED_AT, 2));

                assertThat(republished.ownerKey())
                        .as("the server no longer knows the owner")
                        .isNull();
                aliceStream.await(frame -> isTaskNotification(frame, aliceTask), ofSeconds(5));
                bobStream.await(frame -> isTaskNotification(frame, bobTask), ofSeconds(5));
                assertThat(bobStream.received(frame -> isTaskNotification(frame, aliceTask)))
                        .as("a stream refused the task when it opened never follows it")
                        .isEmpty();
            }
        }
    }

    /**
     * A server that lets a request without credentials through as anonymous. The task an anonymous
     * caller creates is public, so a signed-in caller's stream follows it; a task with no owner at
     * all is followed by nobody.
     */
    @Test
    void aSignedInCallersStreamFollowsAnAnonymousCallersTaskButNotOneNobodyOwns() throws Exception {
        stopServer();
        fixture = OwnedTasksFixture.admittingAnonymous();
        startServer(fixture::configure, fixture::register);
        var publicTask = UUID.randomUUID().toString();
        var aliceTask = UUID.randomUUID().toString();
        var unowned = UUID.randomUUID().toString();
        fixture.startExternally(TaskSnapshot.working(unowned, CREATED_AT, 1));
        try (var anonymous = anonymous();
                var alice = signedIn(ALICE)) {
            assertThat(anonymous.post(start(1, publicTask))).isSuccess().hasResultType("task");
            assertThat(alice.post(start(1, aliceTask))).isSuccess().hasResultType("task");
            assertThat(alice.post(get(2, publicTask))).isSuccess().hasId(2);
            assertThat(alice.post(get(3, unowned))).isJsonRpcError().hasErrorCode(-32602);
            assertThat(anonymous.post(get(3, unowned))).isJsonRpcError().hasErrorCode(-32602);
            assertThat(anonymous.post(get(4, aliceTask))).isJsonRpcError().hasErrorCode(-32602);

            try (var aliceStream = alice.openPostStream(null, listen(publicTask, aliceTask, unowned));
                    var anonymousStream = anonymous.openPostStream(null, listen(publicTask, aliceTask, unowned))) {
                var aliceAck = aliceStream.await(TaskOwnerBindingTest::isAcknowledgement, ofSeconds(5));
                var anonymousAck = anonymousStream.await(TaskOwnerBindingTest::isAcknowledgement, ofSeconds(5));
                assertThat(aliceAck.data()).contains(publicTask, aliceTask).doesNotContain(unowned);
                assertThat(anonymousAck.data()).contains(publicTask).doesNotContain(aliceTask, unowned);

                tasks(server).publish(TaskSnapshot.working(unowned, CREATED_AT, 2));
                tasks(server).publish(TaskSnapshot.working(aliceTask, CREATED_AT, 2));
                tasks(server).publish(TaskSnapshot.working(publicTask, CREATED_AT, 2));

                aliceStream.await(frame -> isTaskNotification(frame, publicTask), ofSeconds(5));
                anonymousStream.await(frame -> isTaskNotification(frame, publicTask), ofSeconds(5));
                assertThat(aliceStream.received(frame -> isTaskNotification(frame, unowned)))
                        .as("nobody follows a task nobody owns")
                        .isEmpty();
                assertThat(anonymousStream.received(
                                frame -> isTaskNotification(frame, unowned) || isTaskNotification(frame, aliceTask)))
                        .as("an anonymous caller follows public tasks only")
                        .isEmpty();
            }
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

    /**
     * A connector may authorize from {@code ctx.securityContext()} too. Every path must hand it the
     * caller of the request being served: not the creator, not anonymous.
     */
    @Test
    void theConnectorReceivesTheCallerOfEachRequestOnEveryTaskPath() throws Exception {
        var taskId = UUID.randomUUID().toString();
        fixture.startExternally(TaskSnapshot.working(taskId, CREATED_AT, 1));
        try (var alice = signedIn(ALICE);
                var bob = signedIn(BOB)) {
            assertThat(alice.post(get(1, taskId))).isSuccess();
            assertThat(bob.post(get(2, taskId))).isSuccess();
            assertThat(alice.post("""
                    {"jsonrpc":"2.0","id":3,"method":"tasks/update","params":{"taskId":"%s","inputResponses":{}}}
                    """.formatted(taskId))).isSuccess();
            assertThat(bob.post("""
                    {"jsonrpc":"2.0","id":4,"method":"tasks/cancel","params":{"taskId":"%s"}}
                    """.formatted(taskId))).isSuccess();
            try (var stream = bob.openPostStream(null, listen(taskId))) {
                stream.await(TaskOwnerBindingTest::isAcknowledgement, ofSeconds(5));
            }

            assertThat(fixture.connectorCalls())
                    .as("a task not cached as owned is read for its owner before an update or a cancel")
                    .containsExactly(
                            "get:" + taskId + ":alice",
                            "get:" + taskId + ":bob",
                            "get:" + taskId + ":alice",
                            "update:" + taskId + ":alice",
                            "get:" + taskId + ":bob",
                            "cancel:" + taskId + ":bob",
                            "get:" + taskId + ":bob");
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
