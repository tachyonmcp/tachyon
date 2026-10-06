package dev.tachyonmcp.docs.extensions.tasks;

import dev.tachyonmcp.api.server.domain.TaskResult;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.api.server.features.tasks.TaskState;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.config.SessionConfig;
import dev.tachyonmcp.docs.ForkedMain;
import dev.tachyonmcp.extensions.tasks.TasksExtension;
import dev.tachyonmcp.testkit.Mcp20251125Client;
import dev.tachyonmcp.testkit.McpTestServers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static dev.tachyonmcp.docs.ForkedMain.DOCUMENTED_PORT;
import static dev.tachyonmcp.docs.JsonRpc.declaring;
import static dev.tachyonmcp.docs.JsonRpc.request;
import static dev.tachyonmcp.testkit.McpHttpResponseAssert.assertThatResponse;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TasksExtensionDocsTest {

    private static final Clock CLOCK = Clock.systemUTC();

    private static JsonNode call(dev.tachyonmcp.testkit.McpClient client, String method, String params)
            throws Exception {
        return assertThatResponse(client.post(request(method, params)))
                .hasStatus(200)
                .isSuccess()
                .result();
    }

    @Test
    @Timeout(60)
    void mainReturnsATaskFromTheToolAndServesGetUpdateAndCancelThroughTheConnector() throws Exception {
        try (var ignored = ForkedMain.start("dev.tachyonmcp.docs.extensions.tasks.TasksServer");
                var client = declaring(DOCUMENTED_PORT, TasksExtension.ID)) {
            var started = call(client, "tools/call", """
                    {"name":"book_appointment","arguments":{}}
                    """);
            assertThat(started.path("resultType").asString()).isEqualTo("task");
            var taskId = started.path("taskId").asString();
            assertThat(taskId).startsWith("wf-");
            assertThat(started.path("status").asString()).isEqualTo("working");

            var working = call(client, "tasks/get", "{\"taskId\":\"%s\"}".formatted(taskId));
            assertThat(working.path("status").asString()).isEqualTo("working");

            call(client, "tasks/update", """
                    {"taskId":"%s","inputResponses":{"approval":{"approved":true}}}
                    """.formatted(taskId));
            var completed = call(client, "tasks/get", "{\"taskId\":\"%s\"}".formatted(taskId));
            assertThat(completed.path("status").asString()).isEqualTo("completed");
            assertThat(completed.path("result").path("structuredContent").path("bookingId").asString())
                    .isEqualTo("booking-" + taskId);

            var other = call(client, "tools/call", """
                    {"name":"book_appointment","arguments":{}}
                    """).path("taskId").asString();
            assertThat(call(client, "tasks/cancel", "{\"taskId\":\"%s\"}".formatted(other))
                            .path("resultType")
                            .asString())
                    .isEqualTo("complete");
            assertThat(call(client, "tasks/get", "{\"taskId\":\"%s\"}".formatted(other))
                            .path("status")
                            .asString())
                    .isEqualTo("cancelled");

            var extensions = call(client, "server/discover", "{}").path("capabilities").path("extensions");
            assertThat(extensions.has(TasksExtension.ID)).isTrue();
        }
    }

    @Test
    void publishCachesNewerRevisionsAndIgnoresOlderOnes() {
        var workflows = new Workflows();
        try (var server = McpTestServers.start(
                b -> b.withExtension(TasksExtension.class, t -> t.connector(workflows.connector())), s -> {})) {
            var createdAt = Instant.parse("2026-09-24T07:00:00Z");
            var tasks = TasksExtension.tasks(server);

            PublishSnapshots.publishWorking(server, "wf-pub", createdAt, CLOCK);
            var working = tasks.get("wf-pub");
            assertThat(working).isNotNull();
            assertThat(working.revision()).isEqualTo(4);
            assertThat(working.status()).isEqualTo(TaskState.WORKING);
            assertThat(working.statusMessage()).isEqualTo("Charging card");

            PublishSnapshots.publishCompleted(server, working, "booking-7", CLOCK);
            var completed = tasks.get("wf-pub");
            assertThat(completed.revision()).isEqualTo(5);
            assertThat(completed.status()).isEqualTo(TaskState.COMPLETED);
            assertThat(completed.result()).isInstanceOf(TaskResult.Completed.class);

            tasks.publish(TaskSnapshot.working("wf-pub", createdAt, 2));
            assertThat(tasks.get("wf-pub").revision()).as("an older revision is ignored").isEqualTo(5);

            assertThatThrownBy(() -> tasks.publish(s -> s.taskId("wf-bad")
                            .status(TaskState.WORKING)
                            .createdAt(createdAt)
                            .lastUpdatedAt(createdAt.minusSeconds(1))
                            .revision(1)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(tasks.get("wf-bad")).as("an invalid snapshot is never published").isNull();

            assertThat(tasks.remove("wf-pub")).isTrue();
            assertThat(tasks.get("wf-pub")).isNull();
        }
    }

    @Test
    void facadeIsUnavailableWithoutTheExtension() {
        try (var server = McpTestServers.start(b -> {}, s -> {})) {
            assertThatThrownBy(() -> TasksExtension.tasks(server)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void toolHandlerHandsTheContextFacadeToTheWorkflowThatPublishesSnapshots() throws Exception {
        var workflows = new Workflows();
        try (var server = ContextFacade.start(workflows, CLOCK);
                var client = declaring(server.port(), TasksExtension.ID)) {
            var started = call(client, "tools/call", """
                    {"name":"book","arguments":{}}
                    """);
            var taskId = started.path("taskId").asString();
            assertThat(started.path("resultType").asString()).isEqualTo("task");

            workflows.complete(taskId, "booking-1");

            var cached = TasksExtension.tasks(server).get(taskId);
            assertThat(cached).isNotNull();
            assertThat(cached.status()).isEqualTo(TaskState.COMPLETED);
        }
    }

    @Test
    @Timeout(30)
    void progressReachesTheProgressTokenOfTheCallThatCreatedTheTask() throws Exception {
        var workflows = new Workflows();
        try (var server = ContextFacade.start(workflows, CLOCK);
                var client = new Mcp20251125Client(server.port())) {
            client.initialize();
            try (var stream = client.openGetStream(null)) {
                stream.awaitFirstEventId(ofSeconds(5));
                var response = client.sendRpc("""
                        {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                          "name":"book","arguments":{},"task":{},"_meta":{"progressToken":"tok-1"}}}
                        """);
                var taskId = new tools.jackson.databind.ObjectMapper()
                        .readTree(response.body())
                        .path("result")
                        .path("task")
                        .path("taskId")
                        .asString();

                PublishSnapshots.reportProgress(server, taskId);

                var frame = stream.await(
                        f -> f.data().contains("notifications/progress") && f.data().contains("Charging card"),
                        ofSeconds(5));
                var params = frame.json().path("params");
                assertThat(params.path("progressToken").asString()).isEqualTo("tok-1");
                assertThat(params.path("progress").asDouble()).isEqualTo(40.0);
                assertThat(params.path("total").asDouble()).isEqualTo(100.0);
            }
            PublishSnapshots.reportProgress(server, "unknown-task");
        }
    }

    @Test
    void connectorThatChecksTheSessionHidesTasksFromOtherSessions() throws Exception {
        var workflows = new Workflows();
        try (var server = McpTestServers.start(
                        b -> b.session(SessionConfig.Builder::enabled)
                                .withExtension(
                                        TasksExtension.class, t -> t.connector(SessionScopedConnector.over(workflows))),
                        s -> s.tools().register(
                                        tool -> tool.name("book").taskSupport(dev.tachyonmcp.api.server.features.tasks.TaskSupport.REQUIRED),
                                        (ctx, request) -> {
                                            var id = workflows.start(request.arguments(), ctx.sessionId());
                                            return ToolResult.task(workflows.find(id).snapshot());
                                        }));
                var owner = new Mcp20251125Client(server.port());
                var stranger = new Mcp20251125Client(server.port())) {
            owner.initialize();
            stranger.initialize();
            var taskId = new tools.jackson.databind.ObjectMapper()
                    .readTree(owner.sendRpc("""
                            {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                              "name":"book","arguments":{},"task":{}}}
                            """).body())
                    .path("result")
                    .path("task")
                    .path("taskId")
                    .asString();
            var get = """
                    {"jsonrpc":"2.0","id":3,"method":"tasks/get","params":{"taskId":"%s"}}
                    """.formatted(taskId);

            assertThatResponse(owner.sendRpc(get)).isSuccess();
            assertThatResponse(stranger.sendRpc(get)).isJsonRpcError().hasErrorCode(-32602);
        }
    }

    @Test
    void configuredPollIntervalIsSuggestedWhenTheSnapshotSetsNone() throws Exception {
        var workflows = new Workflows();
        try (var server = RetentionServer.start(workflows.connector());
                var client = declaring(server.port(), TasksExtension.ID)) {
            var taskId = workflows.start();

            var task = call(client, "tasks/get", "{\"taskId\":\"%s\"}".formatted(taskId));

            assertThat(task.path("pollIntervalMs").asLong()).isEqualTo(2000);
        }
    }

    @Test
    @Timeout(30)
    void legacyResultWaitsOnGetUntilTheTaskIsTerminal() throws Exception {
        var workflows = new Workflows();
        try (var server = LegacyResultPolling.start(workflows.connector());
                var client = new Mcp20251125Client(server.port())) {
            client.initialize();
            var taskId = workflows.start();
            CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(800);
                    workflows.complete(taskId, "booking-late");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });

            var response = client.sendRpc("""
                    {"jsonrpc":"2.0","id":2,"method":"tasks/result","params":{"taskId":"%s"}}
                    """.formatted(taskId));

            assertThatResponse(response).hasStatus(200).isSuccess();
            assertThat(response.body()).contains("booking-late");
            assertThat(workflows.lookups()).isGreaterThanOrEqualTo(2);
        }
    }
}
