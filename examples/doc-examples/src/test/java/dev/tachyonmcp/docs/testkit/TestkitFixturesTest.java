package dev.tachyonmcp.docs.testkit;

import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.extensions.tasks.TasksExtension;
import dev.tachyonmcp.testkit.McpTestClients;
import dev.tachyonmcp.testkit.McpTestServers;
import dev.tachyonmcp.testkit.TestObservationListener;
import dev.tachyonmcp.testkit.TestTaskConnector;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TestkitFixturesTest {

    @Test
    void taskConnectorFixtureRecordsWhatTachyonAskedFor() throws Exception {
        // snips-start: testkit_task_connector
        var tasks = new TestTaskConnector().start(TaskSnapshot.working("t-1", Instant.now(), 1));

        var server = McpTestServers.start(
            b -> b.withExtension(TasksExtension.class, t -> t.connector(tasks.connector())),
            s -> {});
        // snips-end: testkit_task_connector
        try (server;
                var client = McpTestClients.latest(server.port())
                        .withExtensions(Map.<String, JsonNode>of(TasksExtension.ID, new ObjectMapper().createObjectNode()))) {
            client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"tasks/get","params":{"taskId":"t-1"}}
                    """);

            // snips-start: testkit_task_assert
            assertThat(tasks.refreshedTaskIds()).containsExactly("t-1");
            // snips-end: testkit_task_assert
        }
    }

    @Test
    void observationListenerFixtureRecordsTheLifecycleInOrder() throws Exception {
        // snips-start: testkit_listener
        var listener = new TestObservationListener();
        var server = McpTestServers.start(b -> b.observability(o -> o.listener(listener)), s -> {});
        // snips-end: testkit_listener
        server.tools().register(tool -> tool.name("ping"), (ctx, request) -> ToolResult.text("pong"));
        try (server;
                var client = McpTestClients.latest(server.port())) {
            client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"ping","arguments":{}}}
                    """);

            // snips-start: testkit_listener_assert
            assertThat(listener.completed()).singleElement()
                .satisfies(c -> assertThat(c.info().method()).isEqualTo("tools/call"));
            // snips-end: testkit_listener_assert
            assertThat(listener.started()).singleElement().satisfies(s -> assertThat(s.info().method()).isEqualTo("tools/call"));
        }
    }

    @Test
    void failingListenerNeverReachesTheHandler() throws Exception {
        var listener = new TestObservationListener()
                .failOnStart(() -> new IllegalStateException("start"))
                .failOnComplete(() -> new IllegalStateException("complete"));
        var server = McpTestServers.start(b -> b.observability(o -> o.listener(listener)), s -> {});
        server.tools().register(tool -> tool.name("ping"), (ctx, request) -> ToolResult.text("pong"));
        try (server;
                var client = McpTestClients.latest(server.port())) {
            var response = client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"ping","arguments":{}}}
                    """);

            dev.tachyonmcp.testkit.JsonRpcResponseAssert.assertThat(response).isSuccess().hasTextContent("pong");
        }
    }

    @Test
    void discoverAssertCoversTheCapabilities() throws Exception {
        var server = McpTestServers.start(
                b -> {}, s -> s.tools().register(tool -> tool.name("ping"), (ctx, request) -> ToolResult.text("pong")));
        var port = server.port();
        try (server) {
            // snips-start: testkit_discover
            try (var client = McpTestClients.latest(port)) {
                client.discover().isSuccess().hasCapabilities("""
                    {"tools":{}}
                    """);
            }
            // snips-end: testkit_discover
        }
    }
}
