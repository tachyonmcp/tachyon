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
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
        return tool(id, "start", taskId);
    }

    private static String adopt(int id, String taskId) {
        return tool(id, "adopt", taskId);
    }

    private static String tool(int id, String name, String taskId) {
        // language=JSON
        return """
                {"jsonrpc":"2.0","id":%d,"method":"tools/call","params":{
                  "name":"%s","arguments":{"taskId":"%s"},"task":{}}}
                """.formatted(id, name, taskId);
    }

    private static String call(int id, String method, String taskId) {
        // language=JSON
        return """
                {"jsonrpc":"2.0","id":%d,"method":"%s","params":{"taskId":"%s"}}
                """.formatted(id, method, taskId);
    }

    /** The complete task a response carries. The owner never goes on the wire. */
    private static String task(String taskId, String status) {
        // language=JSON
        return """
                {"taskId":"%s","status":"%s","createdAt":"2026-10-15T07:00:00Z",
                 "lastUpdatedAt":"2026-10-15T07:00:00Z","ttl":null}
                """.formatted(taskId, status);
    }

    /** The complete result of the tool call that created {@code taskId}. */
    private static String created(String taskId) {
        return "{\"task\":" + task(taskId, "working") + "}";
    }

    /** The complete {@code tasks/list} result holding these working tasks; the connector lists by id. */
    private static String listed(String... taskIds) {
        return Stream.of(taskIds)
                .sorted()
                .map(taskId -> task(taskId, "working"))
                .collect(Collectors.joining(",", "{\"tasks\":[", "]}"));
    }

    /** Every task method answers {@code caller} for {@code taskId} exactly as it answers an unknown id. */
    private static void assertHidden(Mcp20251125Client caller, String taskId) throws Exception {
        var unknownId = UUID.randomUUID().toString();
        for (var method : List.of("tasks/get", "tasks/result", "tasks/cancel")) {
            var denied = caller.sendRpc(call(3, method, taskId));

            assertThatResponse(denied)
                    .as(method)
                    .isJsonRpcError()
                    .hasErrorCode(-32602)
                    .hasErrorMessageContaining("Task not found");
            assertThat(denied.body())
                    .as("%s: the refusal reads like an unknown id", method)
                    .isEqualTo(caller.sendRpc(call(3, method, unknownId)).body());
        }
    }

    @Test
    void anotherCallerIsToldTheTaskDoesNotExistOnEveryTaskMethod() throws Exception {
        var taskId = UUID.randomUUID().toString();
        try (var alice = signedIn(ALICE);
                var bob = signedIn(BOB)) {
            var created = alice.sendRpc(start(2, taskId));
            assertThatJson(created.body()).inPath("$.result.task.taskId").isEqualTo(taskId);

            assertHidden(bob, taskId);
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
        try (var alice = signedIn(ALICE)) {
            assertThatJson(alice.sendRpc(start(2, taskId)).body())
                    .inPath("$.result.task.taskId")
                    .isEqualTo(taskId);
        }

        stopServer();
        startServer(fixture::configure, fixture::register);

        try (var alice = signedIn(ALICE);
                var bob = signedIn(BOB)) {
            assertHidden(bob, taskId);
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
    void aTaskNobodyOwnsIsHiddenFromEverySignedInCaller() throws Exception {
        var taskId = UUID.randomUUID().toString();
        fixture.startExternally(TaskSnapshot.working(taskId, CREATED_AT, 1));
        try (var alice = signedIn(ALICE);
                var bob = signedIn(BOB)) {
            for (var caller : List.of(alice, bob)) {
                assertHidden(caller, taskId);
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
            assertThatResponse(anonymous.sendRpc(start(2, publicTask)))
                    .isSuccess()
                    .hasId(2)
                    .hasResult(created(publicTask));
            assertThatResponse(alice.sendRpc(start(2, aliceTask)))
                    .isSuccess()
                    .hasId(2)
                    .hasResult(created(aliceTask));

            for (var caller : List.of(anonymous, alice)) {
                assertThatResponse(caller.sendRpc(call(3, "tasks/get", publicTask)))
                        .isSuccess()
                        .hasId(3)
                        .hasResult(task(publicTask, "working"));
            }
            assertThatResponse(alice.sendRpc(call(4, "tasks/list", "-")))
                    .isSuccess()
                    .hasId(4)
                    .hasResult(listed(publicTask, aliceTask));
            assertThatResponse(anonymous.sendRpc(call(4, "tasks/list", "-")))
                    .isSuccess()
                    .hasId(4)
                    .hasResult(listed(publicTask));
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
                assertThatResponse(caller.sendRpc(call(3, "tasks/get", publicTask)))
                        .as("the stored task is still public after a restart")
                        .isSuccess()
                        .hasId(3)
                        .hasResult(task(publicTask, "working"));
                assertHidden(caller, unowned);
            }
            assertThatResponse(alice.sendRpc(call(5, "tasks/list", "-")))
                    .isSuccess()
                    .hasId(5)
                    .hasResult(listed(publicTask, aliceTask));
            assertThatResponse(anonymous.sendRpc(call(5, "tasks/list", "-")))
                    .isSuccess()
                    .hasId(5)
                    .hasResult(listed(publicTask));
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
            assertThat(fixture.connectorCalls())
                    .as("every read of the stored task carries alice")
                    .containsOnly("get:" + taskId + ":alice");
        }
    }

    @Test
    void aReadBeforeTheCreatorsToolReturnsDoesNotMakeTheTaskUnowned() throws Exception {
        var taskId = UUID.randomUUID().toString();
        fixture.startExternally(TaskSnapshot.working(taskId, CREATED_AT, 1));
        try (var alice = signedIn(ALICE);
                var bob = signedIn(BOB)) {
            assertThatResponse(bob.sendRpc(call(2, "tasks/get", taskId)))
                    .as("nobody owns the task yet, so nobody reaches it")
                    .isJsonRpcError()
                    .hasErrorCode(-32602)
                    .hasErrorMessageContaining("Task not found");

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
        fixture.startPublicExternally(TaskSnapshot.working(working, CREATED_AT, 1));
        fixture.startPublicExternally(
                TaskSnapshot.completed(done, CREATED_AT, CREATED_AT, 2, ToolResult.text("finished")));
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
                    .as("every connector call carries the caller of the request it serves")
                    .containsOnly(
                            "get:" + working + ":alice",
                            "get:" + working + ":bob",
                            "get:" + done + ":alice",
                            "awaitResult:" + done + ":alice",
                            "cancel:" + working + ":bob",
                            "list:-:alice");
        }
    }

    /**
     * The store names the owner: what this server remembers may refuse a caller, never admit one. A
     * tool that returns a task id it did not mint gives its caller nothing.
     */
    @Test
    void aToolReturningATaskIdItDidNotMintGivesItsCallerNothing() throws Exception {
        var aliceTask = UUID.randomUUID().toString();
        var unowned = UUID.randomUUID().toString();
        try (var alice = signedIn(ALICE)) {
            assertThatResponse(alice.sendRpc(start(2, aliceTask)))
                    .isSuccess()
                    .hasId(2)
                    .hasResult(created(aliceTask));
        }

        stopServer();
        startServer(fixture::configure, fixture::register);
        fixture.startExternally(TaskSnapshot.working(unowned, CREATED_AT, 1));

        try (var alice = signedIn(ALICE);
                var bob = signedIn(BOB)) {
            var refused = bob.sendRpc(adopt(2, aliceTask));
            assertThatResponse(refused)
                    .as("the store has the task for alice")
                    .isJsonRpcError()
                    .hasErrorCode(-32603);
            assertThat(refused.body())
                    .as("the wire says nothing about ownership")
                    .doesNotContainIgnoringCase("owner")
                    .doesNotContainIgnoringCase("claim");
            assertHidden(bob, aliceTask);
            assertThatResponse(alice.sendRpc(call(4, "tasks/get", aliceTask)))
                    .isSuccess()
                    .hasId(4)
                    .hasResult(task(aliceTask, "working"));
            assertThatResponse(alice.sendRpc(call(5, "tasks/cancel", aliceTask)))
                    .as("the owner still reaches and cancels its task")
                    .isSuccess()
                    .hasId(5)
                    .hasResult(task(aliceTask, "cancelled"));

            for (var caller : List.of(alice, bob)) {
                assertThatResponse(caller.sendRpc(adopt(6, unowned)))
                        .as("a stored task nobody owns cannot be claimed")
                        .isJsonRpcError()
                        .hasErrorCode(-32603);
                assertHidden(caller, unowned);
            }
            assertThat(fixture.connectorCalls())
                    .as("no cancel or result of bob's reached the connector")
                    .noneMatch(call -> call.startsWith("cancel:") && call.endsWith(":bob"))
                    .noneMatch(call -> call.startsWith("awaitResult:") && call.endsWith(":bob"));
        }
    }

    /**
     * A tool returns only a task its connector already has for the caller. An id the store does not
     * have, or does not show the caller, is not the caller's to claim: the claim would hold the id on
     * this server against its real creator.
     */
    @Test
    void aToolCannotReturnATaskItsStoreDoesNotHave() throws Exception {
        var early = UUID.randomUUID().toString();
        try (var alice = signedIn(ALICE);
                var bob = signedIn(BOB)) {
            var refused = bob.sendRpc(adopt(2, early));
            assertThatResponse(refused)
                    .as("the store does not have the task")
                    .isJsonRpcError()
                    .hasErrorCode(-32603);
            assertThat(refused.body())
                    .as("the wire says nothing about ownership")
                    .doesNotContainIgnoringCase("owner")
                    .doesNotContainIgnoringCase("claim");

            assertThatResponse(alice.sendRpc(start(4, early)))
                    .as("the refused claim does not hold the id against its creator")
                    .isSuccess()
                    .hasId(4)
                    .hasResult(created(early));
            assertHidden(bob, early);
            assertThatResponse(alice.sendRpc(call(5, "tasks/get", early)))
                    .isSuccess()
                    .hasId(5)
                    .hasResult(task(early, "working"));
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
