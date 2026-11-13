/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2025_11_25;

import static dev.tachyonmcp.e2e.mcp.OwnedTasksFixture.CREATED_AT;
import static dev.tachyonmcp.e2e.mcp.TasksSupport.tasks;
import static dev.tachyonmcp.e2e.mcp.TestBearerTokens.ALICE;
import static dev.tachyonmcp.e2e.mcp.TestBearerTokens.BOB;
import static dev.tachyonmcp.testkit.McpHttpResponseAssert.assertThatResponse;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.e2e.mcp.OwnedTasksFixture;
import dev.tachyonmcp.testkit.Mcp20251125Client;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * 2025-11-25 Tasks § Security Considerations: "When an authorization context is provided,
 * receivers MUST bind tasks to said context." The task a caller's tool call creates belongs to that
 * caller, from any of its sessions. Anyone else is told the task does not exist, exactly as for an
 * unknown id, and the connector is never asked about it.
 */
@Timeout(30)
class TaskOwnerBindingTest extends AbstractStatefulMcpE2eTest {

    private OwnedTasksFixture fixture = new OwnedTasksFixture();

    @BeforeEach
    void startSecuredTasksServer() {
        fixture = new OwnedTasksFixture();
        startServer(fixture::configure, fixture::register);
    }

    private Mcp20251125Client signedIn(String token) throws Exception {
        var client = new Mcp20251125Client(port, token);
        client.initialize();
        return client;
    }

    private static String start(int id, String taskId) {
        // language=JSON
        return """
                {"jsonrpc":"2.0","id":%d,"method":"tools/call","params":{
                  "name":"start","arguments":{"taskId":"%s"},"task":{}}}
                """.formatted(id, taskId);
    }

    private static String call(int id, String method, String taskId) {
        // language=JSON
        return """
                {"jsonrpc":"2.0","id":%d,"method":"%s","params":{"taskId":"%s"}}
                """.formatted(id, method, taskId);
    }

    @Test
    void anotherCallerIsToldTheTaskDoesNotExistOnEveryTaskMethod() throws Exception {
        var taskId = UUID.randomUUID().toString();
        var unknownId = UUID.randomUUID().toString();
        try (var alice = signedIn(ALICE);
                var bob = signedIn(BOB)) {
            var created = alice.sendRpc(start(2, taskId));
            assertThatJson(created.body()).inPath("$.result.task.taskId").isEqualTo(taskId);

            for (var method : List.of("tasks/get", "tasks/result", "tasks/cancel")) {
                var denied = bob.sendRpc(call(3, method, taskId));
                var unknown = bob.sendRpc(call(3, method, unknownId));

                assertThatResponse(denied)
                        .as(method)
                        .isJsonRpcError()
                        .hasErrorCode(-32602)
                        .hasErrorMessageContaining("Task not found");
                assertThat(denied.body())
                        .as("%s: the refusal reads like an unknown id", method)
                        .isEqualTo(unknown.body());
            }

            assertThat(fixture.connectorCalls())
                    .as("the connector is never asked about a task the caller does not own")
                    .noneMatch(call -> call.contains(":" + taskId + ":bob"));
            var aliceGet = alice.sendRpc(call(4, "tasks/get", taskId));
            assertThatJson(aliceGet.body()).inPath("$.result.taskId").isEqualTo(taskId);
        }
    }

    @Test
    void aTaskOwnedByAnotherCallerIsMissingFromTheList() throws Exception {
        var taskId = UUID.randomUUID().toString();
        try (var alice = signedIn(ALICE);
                var bob = signedIn(BOB)) {
            alice.sendRpc(start(2, taskId));

            var aliceList = alice.sendRpc("""
                    {"jsonrpc":"2.0","id":3,"method":"tasks/list","params":{}}
                    """);
            var bobList = bob.sendRpc("""
                    {"jsonrpc":"2.0","id":3,"method":"tasks/list","params":{}}
                    """);

            assertThatJson(aliceList.body())
                    .inPath("$.result.tasks[*].taskId")
                    .isArray()
                    .containsExactly(taskId);
            assertThatJson(bobList.body()).inPath("$.result.tasks").isArray().isEmpty();
        }
    }

    /**
     * The owner travels with the stored task, so a server that has never seen it (restarted, or
     * another node on the same connector) still refuses everyone but its owner.
     */
    @Test
    void aRestartedServerStillHidesTheTaskFromAnotherCaller() throws Exception {
        var taskId = UUID.randomUUID().toString();
        var unknownId = UUID.randomUUID().toString();
        try (var alice = signedIn(ALICE)) {
            assertThatJson(alice.sendRpc(start(2, taskId)).body())
                    .inPath("$.result.task.taskId")
                    .isEqualTo(taskId);
        }

        stopServer();
        startServer(fixture::configure, fixture::register);

        try (var alice = signedIn(ALICE);
                var bob = signedIn(BOB)) {
            for (var method : List.of("tasks/get", "tasks/result", "tasks/cancel")) {
                var denied = bob.sendRpc(call(3, method, taskId));
                var unknown = bob.sendRpc(call(3, method, unknownId));

                assertThatResponse(denied)
                        .as(method)
                        .isJsonRpcError()
                        .hasErrorCode(-32602)
                        .hasErrorMessageContaining("Task not found");
                assertThat(denied.body())
                        .as("%s: the refusal reads like an unknown id", method)
                        .isEqualTo(unknown.body());
            }
            assertThatJson(bob.sendRpc(call(4, "tasks/list", "-")).body())
                    .inPath("$.result.tasks")
                    .isArray()
                    .isEmpty();
            assertThat(fixture.connectorCalls())
                    .as("bob's cancel and result never ran")
                    .noneMatch(call -> call.equals("cancel:" + taskId + ":bob"))
                    .noneMatch(call -> call.equals("awaitResult:" + taskId + ":bob"));

            var aliceGet = alice.sendRpc(call(5, "tasks/get", taskId));
            assertThatJson(aliceGet.body()).inPath("$.result.taskId").isEqualTo(taskId);
            assertThat(aliceGet.body()).as("the owner never goes on the wire").doesNotContain("alice");
            assertThatJson(alice.sendRpc(call(6, "tasks/list", "-")).body())
                    .inPath("$.result.tasks[*].taskId")
                    .isArray()
                    .containsExactly(taskId);
        }
    }

    @Test
    void aTaskNobodyOwnsIsHiddenFromEverySignedInCallerByDefault() throws Exception {
        stopServer();
        fixture = new OwnedTasksFixture(false);
        startServer(fixture::configure, fixture::register);
        var taskId = UUID.randomUUID().toString();
        fixture.startExternally(TaskSnapshot.working(taskId, CREATED_AT, 1));
        try (var alice = signedIn(ALICE);
                var bob = signedIn(BOB)) {
            for (var caller : List.of(alice, bob)) {
                for (var method : List.of("tasks/get", "tasks/result", "tasks/cancel")) {
                    assertThatResponse(caller.sendRpc(call(3, method, taskId)))
                            .as(method)
                            .isJsonRpcError()
                            .hasErrorCode(-32602)
                            .hasErrorMessageContaining("Task not found");
                }
                assertThatJson(caller.sendRpc(call(4, "tasks/list", "-")).body())
                        .inPath("$.result.tasks")
                        .isArray()
                        .isEmpty();
            }
            assertThat(fixture.connectorCalls())
                    .as("no cancel or result reached the connector for a task nobody may reach")
                    .noneMatch(call -> call.startsWith("cancel:") || call.startsWith("awaitResult:"));

            var created = UUID.randomUUID().toString();
            alice.sendRpc(start(5, created));
            assertThatJson(alice.sendRpc(call(6, "tasks/get", created)).body())
                    .inPath("$.result.taskId")
                    .isEqualTo(created);
        }
    }

    /**
     * A server that lets a request without credentials through as anonymous. The task an anonymous
     * caller creates is public; a task with no owner at all reaches nobody, anonymous or not.
     */
    @Test
    void anAnonymousCallersTaskIsPublicAndATaskNobodyOwnsReachesNoCaller() throws Exception {
        stopServer();
        fixture = OwnedTasksFixture.admittingAnonymous();
        startServer(fixture::configure, fixture::register);
        var publicTask = UUID.randomUUID().toString();
        var aliceTask = UUID.randomUUID().toString();
        var unowned = UUID.randomUUID().toString();
        try (var anonymous = new Mcp20251125Client(port);
                var alice = signedIn(ALICE)) {
            anonymous.initialize();
            assertThatJson(anonymous.sendRpc(start(2, publicTask)).body())
                    .inPath("$.result.task.taskId")
                    .isEqualTo(publicTask);
            alice.sendRpc(start(2, aliceTask));

            for (var caller : List.of(anonymous, alice)) {
                assertThatJson(caller.sendRpc(call(3, "tasks/get", publicTask)).body())
                        .inPath("$.result.taskId")
                        .isEqualTo(publicTask);
            }
            assertThatJson(alice.sendRpc(call(4, "tasks/list", "-")).body())
                    .inPath("$.result.tasks[*].taskId")
                    .isArray()
                    .containsExactlyInAnyOrder(publicTask, aliceTask);
            assertThatJson(anonymous.sendRpc(call(4, "tasks/list", "-")).body())
                    .inPath("$.result.tasks[*].taskId")
                    .isArray()
                    .containsExactly(publicTask);
            assertThatResponse(anonymous.sendRpc(call(5, "tasks/get", aliceTask)))
                    .as("an anonymous caller never reaches an owned task")
                    .isJsonRpcError()
                    .hasErrorCode(-32602)
                    .hasErrorMessageContaining("Task not found");
        }

        stopServer();
        startServer(fixture::configure, fixture::register);
        fixture.startExternally(TaskSnapshot.working(unowned, CREATED_AT, 1));

        try (var anonymous = new Mcp20251125Client(port);
                var alice = signedIn(ALICE)) {
            anonymous.initialize();
            for (var caller : List.of(anonymous, alice)) {
                assertThatJson(caller.sendRpc(call(3, "tasks/get", publicTask)).body())
                        .as("the stored task is still public after a restart")
                        .inPath("$.result.taskId")
                        .isEqualTo(publicTask);
                for (var method : List.of("tasks/get", "tasks/result", "tasks/cancel")) {
                    assertThatResponse(caller.sendRpc(call(4, method, unowned)))
                            .as(method)
                            .isJsonRpcError()
                            .hasErrorCode(-32602)
                            .hasErrorMessageContaining("Task not found");
                }
            }
            assertThatJson(alice.sendRpc(call(5, "tasks/list", "-")).body())
                    .inPath("$.result.tasks[*].taskId")
                    .isArray()
                    .containsExactlyInAnyOrder(publicTask, aliceTask);
            assertThatJson(anonymous.sendRpc(call(5, "tasks/list", "-")).body())
                    .inPath("$.result.tasks[*].taskId")
                    .isArray()
                    .containsExactly(publicTask);
            assertThat(fixture.connectorCalls())
                    .as("no cancel or result reached the connector for a task nobody may reach")
                    .noneMatch(call -> call.startsWith("cancel:") || call.startsWith("awaitResult:"));
        }
    }

    @Test
    void theOwnerReachesItsTaskFromAnotherSession() throws Exception {
        var taskId = UUID.randomUUID().toString();
        try (var first = signedIn(ALICE);
                var second = signedIn(ALICE)) {
            first.sendRpc(start(2, taskId));

            var fromOtherSession = second.sendRpc(call(3, "tasks/get", taskId));

            assertThatJson(fromOtherSession.body()).inPath("$.result.taskId").isEqualTo(taskId);
            assertThat(fixture.connectorCalls()).containsExactly("get:" + taskId + ":alice");
        }
    }

    @Test
    void aReadBeforeTheCreatorsToolReturnsDoesNotMakeTheTaskUnowned() throws Exception {
        var taskId = UUID.randomUUID().toString();
        fixture.startExternally(TaskSnapshot.working(taskId, CREATED_AT, 1));
        try (var alice = signedIn(ALICE);
                var bob = signedIn(BOB)) {
            var early = bob.sendRpc(call(2, "tasks/get", taskId));
            assertThatJson(early.body()).inPath("$.result.taskId").isEqualTo(taskId);

            var created = alice.sendRpc(start(2, taskId));
            assertThatJson(created.body()).inPath("$.result.task.taskId").isEqualTo(taskId);

            assertThatResponse(bob.sendRpc(call(3, "tasks/get", taskId)))
                    .isJsonRpcError()
                    .hasErrorCode(-32602)
                    .hasErrorMessageContaining("Task not found");
            var aliceGet = alice.sendRpc(call(3, "tasks/get", taskId));
            assertThatJson(aliceGet.body()).inPath("$.result.taskId").isEqualTo(taskId);
        }
    }

    /**
     * A connector may authorize from {@code ctx.securityContext()} too. Every path must hand it the
     * caller of the request being served: not the creator, not anonymous.
     */
    @Test
    void theConnectorReceivesTheCallerOfEachRequestOnEveryTaskPath() throws Exception {
        var working = UUID.randomUUID().toString();
        var done = UUID.randomUUID().toString();
        fixture.startExternally(TaskSnapshot.working(working, CREATED_AT, 1));
        fixture.startExternally(TaskSnapshot.completed(done, CREATED_AT, CREATED_AT, 2, ToolResult.text("finished")));
        try (var alice = signedIn(ALICE);
                var bob = signedIn(BOB)) {
            alice.sendRpc(call(2, "tasks/get", working));
            bob.sendRpc(call(2, "tasks/get", working));
            alice.sendRpc(call(3, "tasks/result", done));
            bob.sendRpc(call(3, "tasks/cancel", working));
            alice.sendRpc("""
                    {"jsonrpc":"2.0","id":4,"method":"tasks/list","params":{}}
                    """);

            assertThat(fixture.connectorCalls())
                    .as("a task not cached as owned is read for its owner before a result or a cancel")
                    .containsExactly(
                            "get:" + working + ":alice",
                            "get:" + working + ":bob",
                            "get:" + done + ":alice",
                            "awaitResult:" + done + ":alice",
                            "get:" + working + ":bob",
                            "get:" + working + ":bob",
                            "cancel:" + working + ":bob",
                            "get:" + working + ":bob",
                            "list:-:alice");
        }
    }

    @Test
    void aTaskPublishedWithoutAnOwnerCannotBeClaimedByTheToolThatReturnsIt() throws Exception {
        var taskId = UUID.randomUUID().toString();
        var published = TaskSnapshot.working(taskId, CREATED_AT, 1);
        tasks(server).publish(published);
        try (var alice = signedIn(ALICE)) {
            var refused = alice.sendRpc(start(2, taskId));

            assertThatResponse(refused).isJsonRpcError().hasErrorCode(-32603);
            assertThat(refused.body())
                    .as("the wire says nothing about ownership")
                    .doesNotContainIgnoringCase("owner")
                    .doesNotContainIgnoringCase("claim");
            assertThat(tasks(server).get(taskId)).isEqualTo(published);
        }
    }
}
