/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.tasks;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.testkit.Mcp20251125Client;
import dev.tachyonmcp.testkit.Mcp20260728Client;
import dev.tachyonmcp.testkit.McpTestServers;
import dev.tachyonmcp.testkit.TestTaskConnector;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

/** {@link TasksExtension} registered through {@code withExtension}, over the wire. */
class TasksExtensionE2eTest {

    private static final String TASKS_GET = """
            {"jsonrpc":"2.0","id":1,"method":"tasks/get","params":{"taskId":"task-1"}}
            """;

    @Test
    void extensionIsAdvertisedOnceAndGatesTasksUntilDeclared() throws Exception {
        var connector = new TestTaskConnector().publish(working("task-1"));
        try (var server = startServer(connector, t -> {});
                var undeclared = new Mcp20260728Client(server.port());
                var declared = new Mcp20260728Client(server.port())
                        .withExtensions(Map.of(TasksExtension.ID, JsonNodeFactory.instance.objectNode()))) {
            undeclared.discover().isSuccess().hasCapabilities("""
                    {"extensions":{"io.modelcontextprotocol/tasks":{}}}
                    """);

            var rejected = undeclared.post(TASKS_GET);
            assertThat(rejected.statusCode()).isEqualTo(400);
            assertThatJson(rejected.body()).isEqualTo("""
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

            var accepted = declared.post(TASKS_GET);
            assertThat(accepted.statusCode()).isEqualTo(200);
            assertThatJson(accepted.body())
                    .inPath("$.result")
                    .isObject()
                    .containsEntry("taskId", "task-1")
                    .containsEntry("status", "working");
        }
    }

    @Test
    void configuredPageSizeAppliesToLegacyTasksList() throws Exception {
        var connector = new TestTaskConnector()
                .publish(working("task-a"))
                .publish(working("task-b"))
                .publish(working("task-c"));
        try (var server = startServer(connector, t -> t.pageSize(2));
                var client = new Mcp20251125Client(server.port())) {
            client.initialize();

            var page1 = client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"tasks/list"}
                    """);
            assertThatJson(page1.body()).inPath("$.result.tasks").isArray().hasSize(2);
            var cursor = extractCursor(page1.body());

            var page2 = client.post("""
                    {"jsonrpc":"2.0","id":2,"method":"tasks/list","params":{"cursor":"%s"}}
                    """.formatted(cursor));
            assertThatJson(page2.body()).inPath("$.result.tasks").isArray().hasSize(1);
            assertThatJson(page2.body()).inPath("$.result").isObject().doesNotContainKey("nextCursor");
        }
    }

    @Test
    void tasksRunOnlyWhenTheExtensionIsRegistered() throws Exception {
        try (var server = McpTestServers.start(builder -> {}, it -> {});
                var client = new Mcp20251125Client(server.port())) {
            client.initialize();

            assertThatJson(client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"tasks/list"}
                    """).body()).inPath("$.error.code").isEqualTo(-32601);
            assertThat(server.extension(TasksExtension.class)).isEmpty();
        }
    }

    private static String extractCursor(String body) {
        var marker = "\"nextCursor\":\"";
        var start = body.indexOf(marker) + marker.length();
        return body.substring(start, body.indexOf('"', start));
    }

    private static TachyonServer startServer(
            TestTaskConnector connector, java.util.function.Consumer<TasksExtension.Builder> configurer) {
        return McpTestServers.start(
                builder -> builder.withExtension(TasksExtension.class, t -> {
                    t.connector(connector.connector());
                    configurer.accept(t);
                }),
                it -> {});
    }

    private static TaskSnapshot working(String taskId) {
        return TaskSnapshot.working(taskId, Instant.parse("2026-08-27T07:00:00Z"), 1);
    }
}
