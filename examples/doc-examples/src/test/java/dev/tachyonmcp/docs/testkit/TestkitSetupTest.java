package dev.tachyonmcp.docs.testkit;

import dev.tachyonmcp.api.server.features.tools.ToolDescriptor;
import dev.tachyonmcp.api.server.features.tools.ToolFn;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.testkit.McpTestClients;
import dev.tachyonmcp.testkit.McpTestServers;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class TestkitSetupTest {

    @Test
    void startBuildsAPortZeroServerWithTheRegisteredHandlers() throws Exception {
        ToolDescriptor descriptor = ToolDescriptor.builder().name("noop").build();
        ToolFn handler = (ctx, request) -> ToolResult.text("ok");
        // snips-start: testkit_servers
        var server = McpTestServers.start(
            b -> b.session(c -> c.enabled()),
            s -> s.tools().register(descriptor, handler));
        var port = server.port();
        // snips-end: testkit_servers
        try (server) {
            assertThat(port).isPositive();
            assertThat(server.tools().find("noop")).isPresent();
            try (var client = McpTestClients.forVersion(port, "2025-11-25")) {
                assertThat(client.initialize()).as("sessions are on").isNotNull();
            }
        }
    }

    @Test
    void rawClientsPostJsonOverHttpAndBuilderReturnsAnInitializedClient() throws Exception {
        var server = McpTestServers.start(
                b -> b.session(c -> c.enabled()),
                s -> s.tools().register(tool -> tool.name("noop"), (ctx, request) -> ToolResult.text("ok")));
        var port = server.port();
        try (server) {
            // snips-start: testkit_clients
            try (var client = McpTestClients.latest(port)) {
                client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"tools/list"}
                    """);
            }
            // snips-end: testkit_clients
            try (var client = McpTestClients.latest(port)) {
                var response = client.post("""
                        {"jsonrpc":"2.0","id":1,"method":"tools/list"}
                        """);
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.body()).contains("noop");
            }

            // snips-start: testkit_client_builder
            try (var client = McpTestClients.builder(port).protocolVersion("2025-11-25").build()) {
                client.sendRpc("""
                    {"jsonrpc":"2.0","id":1,"method":"ping"}
                    """);
            }
            // snips-end: testkit_client_builder
            try (var client = McpTestClients.builder(port).protocolVersion("2025-11-25").build()) {
                var response = client.sendRpc("""
                        {"jsonrpc":"2.0","id":1,"method":"ping"}
                        """);
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.body()).contains("\"result\"");
            }
        }
    }

    @Test
    void clientsCaptureNotificationsAndStreamPostsExposeFrames() throws Exception {
        var server = McpTestServers.start(
                b -> b.capabilities(c -> c.tools(true)),
                s -> s.tools().register(tool -> tool.name("work"), (ctx, request) -> {
                    ctx.notifications().progress(request.progressToken(), 1, 1, "done");
                    return ToolResult.text("ok");
                }));
        try (server;
                var client = McpTestClients.latest(server.port())) {
            client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"tools/call",
                     "params":{"name":"work","arguments":{},"_meta":{"progressToken":"tok-1"}}}
                    """);

            // snips-start: testkit_notifications
            client.awaitNotification("notifications/progress")
                .satisfies(params -> assertThat(params.path("progressToken").asString()).isEqualTo("tok-1"));
            // snips-end: testkit_notifications

            // snips-start: testkit_post_stream
            try (var stream = client.openPostStream(null, """
                {"jsonrpc":"2.0","id":1,"method":"subscriptions/listen",
                 "params":{"notifications":{"toolsListChanged":true}}}
                """)) {
                stream.await(
                    frame -> frame.data().contains("notifications/subscriptions/acknowledged"),
                    Duration.ofSeconds(5));
            }
            // snips-end: testkit_post_stream
        }
    }
}
