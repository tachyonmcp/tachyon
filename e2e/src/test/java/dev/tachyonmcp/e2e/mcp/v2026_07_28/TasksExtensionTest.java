/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2026_07_28;

import static dev.tachyonmcp.testkit.JsonRpcResponseAssert.assertThat;
import static dev.tachyonmcp.testkit.McpHttpResponseAssert.assertThatResponse;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.json.JsonSchema;
import dev.tachyonmcp.api.server.domain.FormInputRequest;
import dev.tachyonmcp.api.server.domain.InputRequestBundle;
import dev.tachyonmcp.api.server.domain.TaskResult;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.api.server.features.tasks.TaskState;
import dev.tachyonmcp.api.server.features.tasks.TaskSupport;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.e2e.mcp.AbstractStatelessMcpE2eTest;
import dev.tachyonmcp.extensions.tasks.TasksExtension;
import dev.tachyonmcp.testkit.Mcp20260728Client;
import dev.tachyonmcp.testkit.McpClient;
import dev.tachyonmcp.testkit.McpTestClients;
import dev.tachyonmcp.testkit.TestTaskConnector;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

class TasksExtensionTest extends AbstractStatelessMcpE2eTest<McpClient> {

    @Override
    protected Mcp20260728Client createTestClient() {
        return createTestClient(port);
    }

    @Override
    protected Mcp20260728Client createTestClient(int port) {
        return McpTestClients.latest(port);
    }

    @Test
    void taskProducingToolPublishesSnapshotAndGetRefreshesConnector() throws Exception {
        var observedAt = Instant.parse("2026-08-27T07:00:00Z");
        var initial = TaskSnapshot.working("workflow-1", observedAt, 1);
        var completed = TaskSnapshot.builder()
                .from(initial)
                .status(TaskState.COMPLETED)
                .statusMessage("Done")
                .lastUpdatedAt(observedAt.plusSeconds(1))
                .result(TaskResult.completed(Map.of("bookingId", "booking-1")))
                .revision(2)
                .build();
        var taskEngine = new TestTaskConnector().publish(initial);
        startTasksServer(taskEngine, "book", ToolResult.task(initial));

        try (var client = tasksClient()) {
            var callResponse = client.sendRpc("""
                    {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"book","arguments":{}}}
                    """);
            assertThat(callResponse).isSuccess().hasResultType("task").hasId(1).hasResult("""
                    {
                  "taskId":"workflow-1",
                  "status":"working",
                  "createdAt":"2026-08-27T07:00:00Z",
                  "lastUpdatedAt":"2026-08-27T07:00:00Z",
                  "ttlMs":null,
                  "resultType":"task"
                    }
                  """);

            taskEngine.publish(completed);
            var getResponse = client.sendRpc("""
                    {"jsonrpc":"2.0","id":2,"method":"tasks/get","params":{"taskId":"workflow-1"}}
                    """);
            assertThat(getResponse).isSuccess().hasId(2).hasResult("""
                    {
                  "taskId":"workflow-1",
                  "status":"completed",
                  "statusMessage":"Done",
                  "createdAt":"2026-08-27T07:00:00Z",
                  "lastUpdatedAt":"2026-08-27T07:00:01Z",
                  "ttlMs":null,
                  "resultType":"complete",
                  "result":{
                    "content":[{"type":"text","text":"{\\"bookingId\\":\\"booking-1\\"}"}],
                    "structuredContent":{"bookingId":"booking-1"},
                    "resultType":"complete"
                      }
                    }
                  """);
            assertThat(taskEngine.refreshedTaskIds())
                    .as("tasks/get reads the task from the connector")
                    .containsOnly("workflow-1");
        }
    }

    @Test
    void cancelAcknowledgesBeforeConnectorSettles() throws Exception {
        var initial = TaskSnapshot.working("workflow-cancel", Instant.parse("2026-08-27T07:00:00Z"), 1);
        var taskEngine = new TestTaskConnector().deferCancellation().publish(initial);
        startTasksServer(taskEngine, "book", ToolResult.task(initial));

        try (var client = tasksClient()) {
            var response = client.sendRpc("""
                    {"jsonrpc":"2.0","id":2,"method":"tasks/cancel","params":{"taskId":"workflow-cancel"}}
                    """);

            assertThat(response).isSuccess().hasId(2).hasResult("""
                {"resultType":"complete"}
                """);
            assertThat(taskEngine.cancelledTaskIds()).containsExactly("workflow-cancel");

            var getResponse = client.sendRpc("""
                    {"jsonrpc":"2.0","id":3,"method":"tasks/get","params":{"taskId":"workflow-cancel"}}
                    """);
            assertThat(getResponse).isSuccess().hasId(3).hasResult("""
                    {
                  "taskId":"workflow-cancel",
                  "status":"working",
                  "createdAt":"2026-08-27T07:00:00Z",
                  "lastUpdatedAt":"2026-08-27T07:00:00Z",
                  "ttlMs":null,
                  "resultType":"complete"
                    }
                  """);
            assertThat(taskEngine.refreshedTaskIds())
                    .as("the connector is read for this task only")
                    .containsOnly("workflow-cancel");
        }
    }

    @Test
    void updateForwardsInputToConnectorWithoutReinvokingTool() throws Exception {
        var observedAt = Instant.parse("2026-08-27T07:00:00Z");
        var pendingInput = new InputRequestBundle(
                Map.of(
                        "approval",
                        FormInputRequest.of("Approve booking", JsonSchema.unchecked("{\"type\":\"object\"}"))),
                "approval-round-1");
        var snapshot = TaskSnapshot.builder()
                .taskId("workflow-input")
                .status(TaskState.INPUT_REQUIRED)
                .createdAt(observedAt)
                .lastUpdatedAt(observedAt)
                .pendingInput(pendingInput)
                .revision(1)
                .build();
        var taskEngine = new TestTaskConnector().publish(snapshot);
        var toolInvocations = startTasksServer(taskEngine, "book", ToolResult.task(snapshot));

        try (var client = tasksClient()) {
            var response = client.post("""
                    {"jsonrpc":"2.0","id":2,"method":"tasks/update","params":{
                      "taskId":"workflow-input","inputResponses":{"approval":{"approved":true}}}}
                    """);

            assertThat(response).isSuccess();
            assertThat(taskEngine.submittedInputs()).singleElement().satisfies(request -> {
                assertThat(request.taskId()).isEqualTo("workflow-input");
                assertThat(request.inputResponses()).isEqualTo(Map.of("approval", Map.of("approved", true)));
                assertThat(request.meta()).isNotNull();
            });
            assertThat(toolInvocations).hasValue(0);
        }
    }

    @Test
    void updateForUnknownTaskReturnsError() throws Exception {
        var snapshot = TaskSnapshot.working("workflow-known", Instant.parse("2026-08-27T07:00:00Z"), 1);
        var taskEngine = new TestTaskConnector().publish(snapshot);
        startTasksServer(taskEngine, "book", ToolResult.task(snapshot));

        try (var client = tasksClient()) {
            var response = client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"tasks/update","params":{
                      "taskId":"never-created","inputResponses":{}}}
                    """);
            assertThat(response).isJsonRpcError().hasErrorCode(-32602);
        }
    }

    @Test
    void cancelAlreadyTerminalTaskAcknowledgesNoOp() throws Exception {
        var completed = TaskSnapshot.builder()
                .taskId("workflow-done")
                .status(TaskState.COMPLETED)
                .createdAt(Instant.parse("2026-08-27T07:00:00Z"))
                .lastUpdatedAt(Instant.parse("2026-08-27T07:00:01Z"))
                .result(TaskResult.completed(Map.of("bookingId", "booking-1")))
                .revision(1)
                .build();
        var taskEngine = new TestTaskConnector().publish(completed);
        startTasksServer(taskEngine, "book", ToolResult.task(completed));

        try (var client = tasksClient()) {
            client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"book","arguments":{}}}
                    """);
            var response = client.sendRpc("""
                    {"jsonrpc":"2.0","id":2,"method":"tasks/cancel","params":{"taskId":"workflow-done"}}
                    """);
            assertThat(response).isSuccess().hasId(2).hasResult("""
                {"resultType":"complete"}
                """);
            assertThat(taskEngine.cancelledTaskIds()).containsExactly("workflow-done");
        }
    }

    @Test
    void taskMethodsRequireClientExtensionDeclaration() throws Exception {
        var snapshot = TaskSnapshot.working("workflow-gated", Instant.parse("2026-08-27T07:00:00Z"), 1);
        var taskEngine = new TestTaskConnector().publish(snapshot);
        startTasksServer(taskEngine, "book", ToolResult.task(snapshot));

        try (var client = createModernTestClient()) {
            var response = client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"book","arguments":{}}}
                    """);
            assertThat(response).isJsonRpcError();
        }
    }

    @Test
    @DisplayName("[SEP-2663][SEP-2243] Mcp-Name mirrors params.taskId on tasks/get, tasks/update and tasks/cancel")
    void nameHeaderMirrorsTaskId() throws Exception {
        var snapshot = TaskSnapshot.working("workflow-routed", Instant.parse("2026-08-27T07:00:00Z"), 1);
        startTasksServer(new TestTaskConnector().publish(snapshot), "book", ToolResult.task(snapshot));
        // language=JSON
        var body = """
                {"jsonrpc":"2.0","id":7,"method":"tasks/get","params":{
                  "taskId":"workflow-routed",
                  "_meta":{
                    "io.modelcontextprotocol/protocolVersion":"2026-07-28",
                    "io.modelcontextprotocol/clientInfo":{"name":"t","version":"1"},
                    "io.modelcontextprotocol/clientCapabilities":{
                      "extensions":{"io.modelcontextprotocol/tasks":{}}}}}}
                """;

        assertThatResponse(postMcpRequest(body, Map.of("Mcp-Method", "tasks/get", "Mcp-Name", "workflow-routed")))
                .hasStatus(200)
                .isSuccess()
                .hasId(7);
        assertThatResponse(postMcpRequest(body, Map.of("Mcp-Method", "tasks/get", "Mcp-Name", "another-task")))
                .hasStatus(400)
                .isJsonRpcError()
                .hasId(7)
                .hasErrorCode(-32020)
                .hasErrorMessageContaining("Mcp-Name")
                .hasErrorMessageContaining("does not match");
        assertThatResponse(postMcpRequest(body, Map.of("Mcp-Method", "tasks/get")))
                .hasStatus(400)
                .isJsonRpcError()
                .hasErrorCode(-32020)
                .hasErrorMessageContaining("Mcp-Name")
                .hasErrorMessageContaining("is required");
        for (var method : new String[] {"tasks/update", "tasks/cancel"}) {
            assertThatResponse(postMcpRequest(
                            body.replace("tasks/get", method),
                            Map.of("Mcp-Method", method, "Mcp-Name", "another-task")))
                    .as(method)
                    .hasStatus(400)
                    .isJsonRpcError()
                    .hasErrorCode(-32020);
        }
    }

    @Test
    @DisplayName("[SEP-2663][SEP-2322] a required-task tool gathers input before it creates the task")
    void requiredTaskToolGathersInputBeforeCreatingTask() throws Exception {
        var snapshot = TaskSnapshot.working("workflow-mrtr", Instant.parse("2026-08-27T07:00:00Z"), 1);
        var taskEngine = new TestTaskConnector().publish(snapshot);
        startServer(
                builder -> builder.withExtension(TasksExtension.class, t -> t.connector(taskEngine.connector())),
                registrar -> registrar
                        .tools()
                        .register(b -> b.name("book").taskSupport(TaskSupport.REQUIRED), (context, request) -> {
                            var responses = request.inputResponses();
                            if (responses == null || !responses.containsKey("user_name")) {
                                return ToolResult.inputRequired(
                                        Map.of(
                                                "user_name",
                                                FormInputRequest.of(
                                                        "What is your name?",
                                                        JsonSchema.unchecked("{\"type\":\"object\"}"))),
                                        "round-1");
                            }
                            return ToolResult.task(snapshot);
                        })
                        .register(
                                b -> b.name("never_a_task").taskSupport(TaskSupport.REQUIRED),
                                (context, request) -> ToolResult.text("sync")));

        try (var client = tasksClient()) {
            // language=JSON
            var round1 = client.sendRpc("""
                    {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"book","arguments":{},
                      "_meta":{"io.modelcontextprotocol/clientCapabilities":{"elicitation":{}}}}}
                    """);
            // language=JSON
            assertThat(round1)
                    .as("no task is minted before the final round")
                    .isSuccess()
                    .hasId(1)
                    .hasResult("""
                    {
                      "resultType":"input_required",
                      "inputRequests":{"user_name":{"method":"elicitation/create","params":{
                        "message":"What is your name?","requestedSchema":{"type":"object"}}}},
                      "requestState":"round-1"
                    }
                    """);

            // language=JSON
            var round2 = client.sendRpc("""
                    {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"book","arguments":{},
                      "inputResponses":{"user_name":{"action":"accept","content":{"name":"Alice"}}},
                      "requestState":"round-1",
                      "_meta":{"io.modelcontextprotocol/clientCapabilities":{"elicitation":{}}}}}
                    """);
            // language=JSON
            assertThat(round2).isSuccess().hasId(2).hasResult("""
                    {
                      "taskId":"workflow-mrtr",
                      "status":"working",
                      "createdAt":"2026-08-27T07:00:00Z",
                      "lastUpdatedAt":"2026-08-27T07:00:00Z",
                      "ttlMs":null,
                      "resultType":"task"
                    }
                    """);

            // language=JSON
            var sync = client.sendRpc("""
                    {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"never_a_task","arguments":{}}}
                    """);
            assertThat(sync)
                    .as("a complete non-task result from a required-task tool stays an error")
                    .isJsonRpcError()
                    .hasErrorCode(-32603);
        }
    }

    private AtomicInteger startTasksServer(TestTaskConnector taskEngine, String toolName, ToolResult result) {
        final var toolInvocations = new AtomicInteger();
        startServer(
                builder -> builder.withExtension(TasksExtension.class, t -> t.connector(taskEngine.connector())),
                registrar -> registrar
                        .tools()
                        .register(b -> b.name(toolName).taskSupport(TaskSupport.REQUIRED), (context, request) -> {
                            toolInvocations.incrementAndGet();
                            return result;
                        }));
        return toolInvocations;
    }

    private Mcp20260728Client tasksClient() {
        return createModernTestClient()
                .withExtensions(Map.of(TasksExtension.ID, JsonNodeFactory.instance.objectNode()));
    }
}
