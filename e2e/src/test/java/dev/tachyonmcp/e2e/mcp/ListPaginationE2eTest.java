/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.server.domain.TextResourceContents;
import dev.tachyonmcp.api.server.features.prompts.PromptDescriptor;
import dev.tachyonmcp.api.server.features.resources.ResourceDescriptor;
import dev.tachyonmcp.api.server.features.resources.ResourceFn;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.extensions.tasks.TasksExtension;
import dev.tachyonmcp.testkit.Mcp20251125Client;
import dev.tachyonmcp.testkit.McpClient;
import dev.tachyonmcp.testkit.TestTaskConnector;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ListPaginationE2eTest extends AbstractStatelessMcpE2eTest<McpClient> {

    @Override
    protected Mcp20251125Client createTestClient() {
        return createTestClient(port);
    }

    @Override
    protected Mcp20251125Client createTestClient(int port) {
        return new Mcp20251125Client(port);
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ResourceFn EMPTY_RESOURCE =
            (ctx, request) -> TextResourceContents.of(request.uri(), "", "text/plain");

    @Test
    void resourcesListReturnsConfiguredPageSize() throws Exception {
        startServer(
                b -> b.capabilities(c -> c.resources().resourcesPageSize(2)),
                s -> s.resources()
                        .register(resource("res-a"), EMPTY_RESOURCE)
                        .register(resource("res-b"), EMPTY_RESOURCE)
                        .register(resource("res-c"), EMPTY_RESOURCE));

        try (var client = createTestClient()) {
            client.initialize();

            var page1 = client.post("""
                {"jsonrpc":"2.0","id":1,"method":"resources/list"}
                """);
            var root1 = MAPPER.readTree(page1.body());
            assertThat(root1.at("/result/resources").size()).isEqualTo(2);
            var cursor = root1.at("/result/nextCursor").asString(null);
            assertThat(cursor).isNotNull();

            var page2 = client.post("""
                {"jsonrpc":"2.0","id":2,"method":"resources/list","params":{"cursor":"%s"}}
                """.formatted(cursor));
            var root2 = MAPPER.readTree(page2.body());
            assertThat(root2.at("/result/resources").size()).isEqualTo(1);
            assertThat(root2.at("/result/nextCursor").asString(null)).isNull();
        }
    }

    @Test
    void resourcesListRejectsInvalidCursor() throws Exception {
        startServer(
                b -> b.capabilities(c -> c.resources().resourcesPageSize(2)),
                s -> s.resources().register(resource("res-a"), EMPTY_RESOURCE));

        try (var client = createTestClient()) {
            client.initialize();

            var response = client.post("""
                {"jsonrpc":"2.0","id":1,"method":"resources/list","params":{"cursor":"garbage-cursor-xyz"}}
                """);
            var root = MAPPER.readTree(response.body());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(root.at("/error/code").asInt()).isEqualTo(-32602);
            assertThat(root.at("/error/message").asString()).isEqualTo("Invalid cursor");
        }
    }

    @Test
    void promptsListReturnsConfiguredPageSize() throws Exception {
        startServer(
                b -> b.capabilities(c -> c.prompts().promptsPageSize(2)),
                s -> s.prompts()
                        .register(PromptDescriptor.of("p-a", null), List.of())
                        .register(PromptDescriptor.of("p-b", null), List.of())
                        .register(PromptDescriptor.of("p-c", null), List.of()));

        try (var client = createTestClient()) {
            client.initialize();

            var page1 = client.post("""
                {"jsonrpc":"2.0","id":1,"method":"prompts/list"}
                """);
            var root1 = MAPPER.readTree(page1.body());
            assertThat(root1.at("/result/prompts").size()).isEqualTo(2);
            var cursor = root1.at("/result/nextCursor").asString(null);
            assertThat(cursor).isNotNull();

            var page2 = client.post("""
                {"jsonrpc":"2.0","id":2,"method":"prompts/list","params":{"cursor":"%s"}}
                """.formatted(cursor));
            var root2 = MAPPER.readTree(page2.body());
            assertThat(root2.at("/result/prompts").size()).isEqualTo(1);
            assertThat(root2.at("/result/nextCursor").asString(null)).isNull();
        }
    }

    @Test
    void tasksListReturnsConfiguredPageSize() throws Exception {
        var taskEngine = new TestTaskConnector()
                .publish(working("task-a"))
                .publish(working("task-b"))
                .publish(working("task-c"));
        startServer(it -> it.withExtension(
                TasksExtension.class, t -> t.connector(taskEngine.connector()).pageSize(2)));

        try (var client = createTestClient()) {
            client.initialize();

            var page1 = client.post("""
                {"jsonrpc":"2.0","id":1,"method":"tasks/list"}
                """);
            var root1 = MAPPER.readTree(page1.body());
            assertThat(root1.at("/result/tasks").size()).isEqualTo(2);
            var cursor = root1.at("/result/nextCursor").asString(null);
            assertThat(cursor).isNotNull();

            var page2 = client.post("""
                {"jsonrpc":"2.0","id":2,"method":"tasks/list","params":{"cursor":"%s"}}
                """.formatted(cursor));
            var root2 = MAPPER.readTree(page2.body());
            assertThat(root2.at("/result/tasks").size()).isEqualTo(1);
            assertThat(root2.at("/result/nextCursor").asString(null)).isNull();
        }
    }

    private static ResourceDescriptor resource(String name) {
        return ResourceDescriptor.of(name, "test://" + name, null, null);
    }

    private static TaskSnapshot working(String taskId) {
        return TaskSnapshot.working(taskId, Instant.parse("2026-08-27T07:00:00Z"), 1);
    }
}
