/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2025_11_25;

import static dev.tachyonmcp.testkit.JsonRpcResponseAssert.assertThat;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.api.server.features.tasks.TaskSupport;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.e2e.mcp.AbstractStatelessMcpE2eTest;
import dev.tachyonmcp.testkit.Mcp20251125Client;
import dev.tachyonmcp.testkit.TestTaskConnector;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Legacy tasks on a stateless server: no session owns a task, so no session is ever notified. */
class StatelessTasksTest extends AbstractStatelessMcpE2eTest<Mcp20251125Client> {

    private static final Instant CREATED_AT = Instant.parse("2026-09-24T07:00:00Z");

    private final TestTaskConnector taskEngine = new TestTaskConnector();

    @Override
    protected Mcp20251125Client createTestClient() {
        return createTestClient(port);
    }

    @Override
    protected Mcp20251125Client createTestClient(int port) {
        return new Mcp20251125Client(port);
    }

    @Override
    protected void startDefaultServer() {
        startServer(builder -> builder.capabilities(c -> c.tasks(taskEngine.connector())), registrar -> {
            registrar.tools().register(b -> b.name("book").taskSupport(TaskSupport.REQUIRED), (context, request) -> {
                var snapshot = TaskSnapshot.working("stateless-task", CREATED_AT, 1);
                taskEngine.publish(snapshot);
                return ToolResult.task(snapshot);
            });
        });
    }

    @Test
    void taskAugmentedCallHandsOffWithoutSession() throws Exception {
        try (var client = createTestClient()) {
            assertThat(client.initialize()).as("stateless: no session id").isNull();

            var response = client.sendRpc("""
                    {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                      "name":"book","arguments":{},"task":{},"_meta":{"progressToken":"tok"}}}
                    """);
            assertThatJson(response.body()).inPath("$.result.task.taskId").isEqualTo("stateless-task");
            assertThatJson(response.body()).inPath("$.result.task.status").isEqualTo("working");

            var get = client.sendRpc("""
                    {"jsonrpc":"2.0","id":3,"method":"tasks/get","params":{"taskId":"stateless-task"}}
                    """);
            assertThat(get).isSuccess();
            assertThatJson(get.body()).inPath("$.result.taskId").isEqualTo("stateless-task");

            // Nobody owns it: progress has no session to go to and must be dropped quietly.
            server.tasks().reportProgress("stateless-task", 0.5, 1.0, "halfway");

            assertThat(client.notifications())
                    .as("a stateless server sends no session notifications for tasks")
                    .noneMatch(n -> n.method().startsWith("notifications/tasks")
                            || n.method().equals("notifications/progress"));
        }
    }
}
