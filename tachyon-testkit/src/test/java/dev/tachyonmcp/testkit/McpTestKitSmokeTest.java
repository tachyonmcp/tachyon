/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.testkit;

import static dev.tachyonmcp.testkit.McpHttpResponseAssert.assertThatResponse;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tachyonmcp.api.json.JsonSchema;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.TachyonServer;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Verifies the testkit client/helpers against a real port-0 server. */
class McpTestKitSmokeTest {

    private static final JsonSchema ECHO_SCHEMA = JsonSchema.unchecked("""
            {
              "type": "object",
              "properties": {
                "message": {"type": "string"}
              },
              "required": ["message"]
            }
            """);

    private static final ObjectMapper JSON = new ObjectMapper();

    private static TachyonServer server;
    private static int port;

    @BeforeAll
    static void startEchoServer() {
        server = McpTestServers.start(b -> b.session(c -> c.enabled()), s -> {
            s.tools()
                    .register(
                            d -> d.name("echo")
                                    .description("Echo back the input")
                                    .inputSchema(ECHO_SCHEMA),
                            (ctx, request) -> ToolResult.text(
                                    "echo:" + request.arguments().stringOr("message", "")));
            s.tools()
                    .register(
                            d -> d.name("slow-progress").description("Emits a progress notification then completes"),
                            (ctx, request) -> {
                                ctx.notifications().progress(request.progressToken(), 1, 1, "tick");
                                return ToolResult.text("done");
                            });
        });
        port = server.port();
    }

    @AfterAll
    static void closeServer() {
        server.close();
    }

    /** The 2026-07-28 client shapes {@code _meta} and headers, so a bare request is accepted. */
    @Test
    void latestClientShapes2026Requests() throws Exception {
        try (var client = McpTestClients.latest(port)) {
            var list = client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"tools/list"}
                    """);
            assertThat(list.statusCode()).as(list.body()).isEqualTo(200);
            assertThat(list.body()).contains("echo");

            var call = client.post("""
                    {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"echo","arguments":{"message":"hi"}}}
                    """);
            assertThat(call.statusCode()).as(call.body()).isEqualTo(200);
            assertThat(call.body()).contains("echo");
            assertThat(call.headers().firstValue("MCP-Session-Id")).isEmpty();
        }
    }

    /** Declared extensions are merged into {@code clientCapabilities.extensions}. */
    @Test
    void withExtensionsWritesClientCapabilities() throws Exception {
        try (var client = McpTestClients.latest(port)) {
            client.withExtensions(Map.of("com.example/cool", JSON.readTree("{\"client\":true}")));
            var call = client.post("""
                    {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"echo","arguments":{"message":"x"}}}
                    """);
            assertThat(call.statusCode()).as(call.body()).isEqualTo(200);
        }
    }

    /** The 2025-11-25 client performs the initialize handshake against a session-enabled server. */
    @Test
    void forVersion2025ClientInitializes() throws Exception {
        try (var client = McpTestClients.forVersion(port, "2025-11-25")) {
            assertThat(client.initialize()).isNotNull();
            var ping = client.sendRpc("""
                    {"jsonrpc":"2.0","id":4,"method":"ping"}
                    """);
            assertThatResponse(ping).isSuccess();
        }
    }

    /** A progress notification emitted during a tools/call is captured and awaited (2025 session POST). */
    @Test
    void awaitNotificationCatches2025ProgressOnSessionPost() throws Exception {
        try (var client = McpTestClients.forVersion(port, "2025-11-25")) {
            var sessionId = client.initialize();
            var call = client.post(sessionId, """
                    {"jsonrpc":"2.0","id":5,"method":"tools/call",
                     "params":{"name":"slow-progress","arguments":{},"_meta":{"progressToken":"tok-2025"}}}
                    """);
            assertThat(call.statusCode()).as(call.body()).isEqualTo(200);
            client.awaitNotification("notifications/progress", Duration.ofSeconds(5))
                    .satisfies(params ->
                            assertThat(params.path("progressToken").asString()).isEqualTo("tok-2025"));
        }
    }

    /** Progress notifications ride the 2026 streaming POST; the buffered response still captures them. */
    @Test
    void awaitNotificationCatches2026ProgressOnSessionlessPost() throws Exception {
        try (var client = McpTestClients.latest(port)) {
            var call = client.post("""
                    {"jsonrpc":"2.0","id":6,"method":"tools/call",
                     "params":{"name":"slow-progress","arguments":{},"_meta":{"progressToken":"tok-2026"}}}
                    """);
            assertThat(call.statusCode()).as(call.body()).isEqualTo(200);
            client.awaitNotification("notifications/progress", Duration.ofSeconds(5))
                    .satisfies(params ->
                            assertThat(params.path("progressToken").asString()).isEqualTo("tok-2026"));
        }
    }

    @Test
    void awaitNotificationPredicateReceivesParamsAndSkipsNonMatchingNotifications() throws Exception {
        try (var client = McpTestClients.latest(port)) {
            client.post("""
                    {"jsonrpc":"2.0","id":7,"method":"tools/call",
                     "params":{"name":"slow-progress","arguments":{},"_meta":{"progressToken":"skip"}}}
                    """);
            client.post("""
                {"jsonrpc":"2.0","id":8,"method":"tools/call",
                 "params":{"name":"slow-progress","arguments":{},"_meta":{"progressToken":"match"}}}
                """);

            var notification = client.awaitNotification(
                    "notifications/progress",
                    params -> params.path("progressToken").asString().equals("match"),
                    Duration.ofMillis(100));

            assertThat(notification.params().path("progressToken").asString()).isEqualTo("match");
        }
    }

    /** Notifications received so far are listed in arrival order and can be cleared. */
    @Test
    void notificationsSnapshotAndClear() throws Exception {
        try (var client = McpTestClients.latest(port)) {
            client.post("""
                    {"jsonrpc":"2.0","id":7,"method":"tools/call",
                     "params":{"name":"slow-progress","arguments":{},"_meta":{"progressToken":"snap"}}}
                    """);
            assertThat(client.notifications()).map(Notification::method).contains("notifications/progress");
            client.clearNotifications();
            assertThat(client.notifications()).isEmpty();
        }
    }

    /** A client-side notification is accepted (2025 session, initialized client). */
    @Test
    void notifySerializesParams() throws Exception {
        try (var client = McpTestClients.forVersion(port, "2025-11-25")) {
            client.initialize();
            var response = client.notify("notifications/initialized", Map.of("ready", true));
            assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
            assertThat(response.body()).isEmpty();
        }
    }

    /** The notification envelope carries params under a {@code params} key when given. */
    @Test
    void notificationEnvelopeIncludesParamsWhenPresent() {
        var json = McpClient.notificationEnvelope("notifications/initialized", Map.of("ready", true));
        // language=JSON
        assertThatJson(json).isEqualTo("""
                {"jsonrpc":"2.0","method":"notifications/initialized","params":{"ready":true}}
                """);
    }

    /** The notification envelope omits the {@code params} key entirely when there are no params. */
    @Test
    void notificationEnvelopeOmitsParamsWhenAbsent() {
        var json = McpClient.notificationEnvelope("notifications/initialized", null);
        // language=JSON
        assertThatJson(json).isEqualTo("""
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                """);
    }

    @Test
    void forVersionRejectsUnknownVersions() {
        assertThatThrownBy(() -> McpTestClients.forVersion(port, "2024-11-05"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2024-11-05");
    }
}
