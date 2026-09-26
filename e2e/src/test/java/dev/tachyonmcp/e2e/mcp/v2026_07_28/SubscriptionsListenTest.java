/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2026_07_28;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.tachyonmcp.api.server.domain.TextResourceContents;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.features.tasks.TasksExtension;
import dev.tachyonmcp.e2e.mcp.AbstractStatelessMcpE2eTest;
import dev.tachyonmcp.testkit.Mcp20260728Client;
import dev.tachyonmcp.testkit.McpTestClients;
import dev.tachyonmcp.testkit.TestTaskConnector;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * MCP 2026-07-28 {@code subscriptions/listen} (SEP-2575): the request-scoped SSE stream that
 * replaces {@code resources/subscribe} and the plain HTTP GET stream for the stateless transport.
 */
class SubscriptionsListenTest extends AbstractStatelessMcpE2eTest<Mcp20260728Client> {

    @Override
    protected Mcp20260728Client createTestClient() {
        return createTestClient(port);
    }

    @Override
    protected Mcp20260728Client createTestClient(int port) {
        return McpTestClients.latest(port);
    }

    @BeforeEach
    void freshServer() {
        startServer(
                b -> b.capabilities(c -> c.tools(true).resources(false, true).prompts(true)));
    }

    @Test
    void acknowledgesFirstWithMatchingSubscriptionId() throws Exception {
        var lines = new CopyOnWriteArrayList<String>();
        try (var client = createModernTestClient()) {
            var response = client.sendStreamingRequest(null, """
                {"jsonrpc":"2.0","id":1,"method":"subscriptions/listen",
                 "params":{"notifications":{"toolsListChanged":true}}}
                """);
            assertThat(response.statusCode()).isEqualTo(200);
            var consume = CompletableFuture.runAsync(() -> response.body().forEach(lines::add));
            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> assertThat(payloads(lines)).isNotEmpty());

            // Raw, unfiltered first "data:" line — proves the ack is the actual first SSE frame on
            // the wire, not just the first non-blank one (payloads() strips blank data lines, which
            // would hide a leading blank priming frame arriving before the ack).
            var firstDataLine = lines.stream()
                    .filter(l -> l.startsWith("data:"))
                    .findFirst()
                    .orElseThrow();
            var firstPayload =
                    firstDataLine.startsWith("data: ") ? firstDataLine.substring(6) : firstDataLine.substring(5);
            // language=JSON
            assertThatJson(firstPayload).isEqualTo("""
                {
                  "jsonrpc": "2.0",
                  "method": "notifications/subscriptions/acknowledged",
                  "params": {
                    "notifications": {"toolsListChanged": true},
                    "_meta": {"io.modelcontextprotocol/subscriptionId": 1}
                  }
                }
                """);

            response.body().close();
            awaitQuietly(consume);
        }
    }

    @Test
    void enforcesRequestedFilterNoUnrequestedNotificationsLeak() throws Exception {
        var lines = new CopyOnWriteArrayList<String>();
        try (var client = createModernTestClient()) {
            var response = client.sendStreamingRequest(null, """
                {"jsonrpc":"2.0","id":1,"method":"subscriptions/listen",
                 "params":{"notifications":{"toolsListChanged":true}}}
                """);
            var consume = CompletableFuture.runAsync(() -> response.body().forEach(lines::add));
            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> assertThat(payloads(lines))
                            .anyMatch(l -> l.contains("notifications/subscriptions/acknowledged")));

            // Trigger a resources change (not requested) then a tools change (requested). Because
            // both go through the same per-stream event loop in submission order, observing the
            // tools notification proves the server already had its chance to (wrongly) deliver the
            // resources one first.
            server.resources()
                    .register(
                            r -> r.name("trigger-resource").uri("test://trigger"),
                            (ctx, req) -> TextResourceContents.of(req.uri(), "anything", "text/plain"));
            server.tools()
                    .register(
                            t -> t.name("trigger-tool").description("d").inputSchema("{\"type\":\"object\"}"),
                            (ctx, req) -> ToolResult.text("x"));

            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() ->
                            assertThat(payloads(lines)).anyMatch(l -> l.contains("notifications/tools/list_changed")));

            assertThat(payloads(lines)).noneMatch(l -> l.contains("notifications/resources/list_changed"));

            response.body().close();
            awaitQuietly(consume);
        }
    }

    @Test
    void pushesResourceUpdatedOnlyForSubscribedUri() throws Exception {
        var lines = new CopyOnWriteArrayList<String>();
        try (var client = createModernTestClient()) {
            var response = client.sendStreamingRequest(null, """
                {"jsonrpc":"2.0","id":1,"method":"subscriptions/listen",
                 "params":{"notifications":{"resourceSubscriptions":["test://a"]}}}
                """);
            var consume = CompletableFuture.runAsync(() -> response.body().forEach(lines::add));
            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> assertThat(payloads(lines))
                            .anyMatch(l -> l.contains("notifications/subscriptions/acknowledged")));

            server.resources().notifyResourceUpdated("test://b");
            server.resources().notifyResourceUpdated("test://a");

            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> assertThat(payloads(lines))
                            .anyMatch(l -> l.contains("notifications/resources/updated") && l.contains("test://a")));

            assertThat(payloads(lines))
                    .filteredOn(l -> l.contains("notifications/resources/updated"))
                    .noneMatch(l -> l.contains("test://b"));

            response.body().close();
            awaitQuietly(consume);
        }
    }

    @Test
    void pushesTaskStatusOnlyForSubscribedTaskId() throws Exception {
        // The connector authorizes each listened task id, so it must know task-a.
        var taskEngine = new TestTaskConnector()
                .publish(TaskSnapshot.working("task-a", Instant.parse("2026-08-28T10:00:00Z"), 0));
        startServer(b -> b.capabilities(c -> c.tools(true).tasks(taskEngine.connector())));

        var lines = new CopyOnWriteArrayList<String>();
        var unsubscribedLines = new CopyOnWriteArrayList<String>();
        try (var client = createModernTestClient()
                .withExtensions(Map.of(TasksExtension.ID, JsonNodeFactory.instance.objectNode()))) {
            var response = client.sendStreamingRequest(null, """
                {"jsonrpc":"2.0","id":1,"method":"subscriptions/listen",
                 "params":{"notifications":{"taskIds":["task-a"]}}}
                """);
            var unsubscribedResponse = client.sendStreamingRequest(null, """
                {"jsonrpc":"2.0","id":2,"method":"subscriptions/listen",
                 "params":{"notifications":{"toolsListChanged":true}}}
                """);
            var consume = CompletableFuture.runAsync(() -> response.body().forEach(lines::add));
            var consumeUnsubscribed =
                    CompletableFuture.runAsync(() -> unsubscribedResponse.body().forEach(unsubscribedLines::add));
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                assertThat(payloads(lines)).anyMatch(l -> l.contains("notifications/subscriptions/acknowledged"));
                assertThat(payloads(unsubscribedLines))
                        .anyMatch(l -> l.contains("notifications/subscriptions/acknowledged"));
            });

            // language=JSON
            assertThatJson(payloads(lines).getFirst()).isEqualTo("""
                {
                  "jsonrpc": "2.0",
                  "method": "notifications/subscriptions/acknowledged",
                    "params": {
                      "notifications": {
                        "taskIds": ["task-a"]
                      },
                      "_meta": {"io.modelcontextprotocol/subscriptionId": 1}
                  }
                }
                """);

            var observedAt = Instant.parse("2026-08-28T10:00:00Z");
            server.tasks().publish(TaskSnapshot.working("task-b", observedAt, 1));
            server.tasks().publish(TaskSnapshot.working("task-a", observedAt, 1));

            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> assertThat(payloads(lines))
                            .anyMatch(l -> l.contains("notifications/tasks") && l.contains("task-a")));

            assertThat(payloads(lines))
                    .filteredOn(l -> l.contains("notifications/tasks"))
                    .noneMatch(l -> l.contains("task-b"));

            // A listener with no task-ID subscription must never receive notifications/tasks, even
            // after both snapshots above were published. Trigger a notification this listener *is*
            // subscribed to and wait for it: deliveries from one publish are dispatched in submission
            // order, so observing this later notification proves the earlier task publishes already
            // had their chance to (wrongly) reach this stream first.
            server.tools()
                    .register(
                            t -> t.name("trigger-tool-unsubscribed")
                                    .description("d")
                                    .inputSchema("{\"type\":\"object\"}"),
                            (ctx, req) -> ToolResult.text("x"));
            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> assertThat(payloads(unsubscribedLines))
                            .anyMatch(l -> l.contains("notifications/tools/list_changed")));

            assertThat(payloads(unsubscribedLines)).noneMatch(l -> l.contains("notifications/tasks"));

            response.body().close();
            unsubscribedResponse.body().close();
            awaitQuietly(consume);
            awaitQuietly(consumeUnsubscribed);
        }
    }

    @Test
    void taskStatusSubscriptionRequiresTasksExtension() throws Exception {
        var taskEngine = new TestTaskConnector();
        startServer(b -> b.capabilities(c -> c.tasks(taskEngine.connector())));

        try (var client = createModernTestClient()) {
            var response = client.post("""
                {"jsonrpc":"2.0","id":1,"method":"subscriptions/listen",
                 "params":{"notifications":{"taskIds":["task-a"]}}}
                """);

            // MISSING_REQUIRED_CLIENT_CAPABILITY maps to HTTP 400, not 200 — see
            // McpResponseMapper (2026-07-28) .error(), same convention as HEADER_MISMATCH et al.
            assertThat(response.statusCode()).isEqualTo(400);
            // language=JSON
            assertThatJson(response.body()).isEqualTo("""
                {
                  "jsonrpc":"2.0",
                  "id":1,
                  "error":{
                    "code":-32021,
                    "message":"Requires the 'io.modelcontextprotocol/tasks' extension",
                    "data":{"requiredCapabilities":{"extensions":{"io.modelcontextprotocol/tasks":{}}}}
                  }
                }
                """);
        }
    }

    @Test
    void demultiplexesConcurrentSubscriptionsById() throws Exception {
        var linesA = new CopyOnWriteArrayList<String>();
        var linesB = new CopyOnWriteArrayList<String>();
        try (var client = createModernTestClient()) {
            var responseA = client.sendStreamingRequest(null, """
                {"jsonrpc":"2.0","id":10,"method":"subscriptions/listen",
                 "params":{"notifications":{"toolsListChanged":true}}}
                """);
            var responseB = client.sendStreamingRequest(null, """
                {"jsonrpc":"2.0","id":20,"method":"subscriptions/listen",
                 "params":{"notifications":{"toolsListChanged":true}}}
                """);
            var consumeA = CompletableFuture.runAsync(() -> responseA.body().forEach(linesA::add));
            var consumeB = CompletableFuture.runAsync(() -> responseB.body().forEach(linesB::add));
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                assertThat(payloads(linesA)).anyMatch(l -> l.contains("acknowledged"));
                assertThat(payloads(linesB)).anyMatch(l -> l.contains("acknowledged"));
            });

            server.tools()
                    .register(
                            t -> t.name("trigger-tool-2").description("d").inputSchema("{\"type\":\"object\"}"),
                            (ctx, req) -> ToolResult.text("x"));

            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                var notifA = payloads(linesA).stream()
                        .filter(l -> l.contains("notifications/tools/list_changed"))
                        .findFirst();
                assertThat(notifA).isPresent();
                // language=JSON
                assertThatJson(notifA.get()).isEqualTo("""
                    {
                      "jsonrpc": "2.0",
                      "method": "notifications/tools/list_changed",
                      "params": {"_meta": {"io.modelcontextprotocol/subscriptionId": 10}}
                    }
                    """);

                var notifB = payloads(linesB).stream()
                        .filter(l -> l.contains("notifications/tools/list_changed"))
                        .findFirst();
                assertThat(notifB).isPresent();
                // language=JSON
                assertThatJson(notifB.get()).isEqualTo("""
                    {
                      "jsonrpc": "2.0",
                      "method": "notifications/tools/list_changed",
                      "params": {"_meta": {"io.modelcontextprotocol/subscriptionId": 20}}
                    }
                    """);
            });

            responseA.body().close();
            responseB.body().close();
            awaitQuietly(consumeA);
            awaitQuietly(consumeB);
        }
    }

    @Test
    void serverStaysResponsiveAfterClientDisconnect() throws Exception {
        var lines = new CopyOnWriteArrayList<String>();
        try (var client = createModernTestClient()) {
            var response = client.sendStreamingRequest(null, """
                {"jsonrpc":"2.0","id":1,"method":"subscriptions/listen",
                 "params":{"notifications":{"toolsListChanged":true}}}
                """);
            var consume = CompletableFuture.runAsync(() -> response.body().forEach(lines::add));
            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> assertThat(payloads(lines)).anyMatch(l -> l.contains("acknowledged")));

            response.body().close();
            awaitQuietly(consume);

            // A dropped subscriber must not wedge the server: a list-change fired after disconnect,
            // and a fresh unrelated request, must both still succeed.
            server.tools()
                    .register(
                            t -> t.name("trigger-tool-3").description("d").inputSchema("{\"type\":\"object\"}"),
                            (ctx, req) -> ToolResult.text("x"));
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                var pingResponse = client.sendRpc(null, """
                    {"jsonrpc":"2.0","id":99,"method":"tools/list","params":{}}
                    """);
                assertThat(pingResponse.body()).contains("\"trigger-tool-3\"");
            });
        }
    }

    @Test
    void gracefullyClosesOnServerShutdown() throws Exception {
        var lines = new CopyOnWriteArrayList<String>();
        try (var client = createModernTestClient()) {
            var response = client.sendStreamingRequest(null, """
                {"jsonrpc":"2.0","id":1,"method":"subscriptions/listen",
                 "params":{"notifications":{"toolsListChanged":true}}}
                """);
            var consume = CompletableFuture.runAsync(() -> response.body().forEach(lines::add));
            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> assertThat(payloads(lines)).anyMatch(l -> l.contains("acknowledged")));

            stopServer();
            consume.get(15, TimeUnit.SECONDS);

            var last = payloads(lines).getLast();
            // language=json
            assertThatJson(last).isEqualTo("""
                    {"jsonrpc":"2.0",
                    "id":1,
                    "result":{
                      "_meta": {"io.modelcontextprotocol/subscriptionId":1},
                      "resultType":"complete"
                      }
                    }
                    """);
        }
    }

    private static void awaitQuietly(CompletableFuture<Void> future) throws Exception {
        try {
            future.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            // Expected: closing the client-side stream mid-read makes the background line reader
            // fail with an I/O-shaped exception. Anything else is a real bug — let it fail the test.
            var cause = e.getCause();
            if (!(cause instanceof IOException) && !(cause instanceof UncheckedIOException)) {
                throw e;
            }
        }
    }

    private static List<String> payloads(List<String> lines) {
        return lines.stream()
                .map(l -> l.startsWith("data: ") ? l.substring(6) : l.startsWith("data:") ? l.substring(5) : "")
                .filter(p -> !p.isBlank())
                .toList();
    }
}
