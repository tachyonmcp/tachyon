/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2025_11_25;

import static dev.tachyonmcp.testkit.McpHttpResponseAssert.assertThatResponse;

import dev.tachyonmcp.api.server.features.tasks.TaskConnector;
import dev.tachyonmcp.api.server.features.tasks.TaskNotFoundException;
import dev.tachyonmcp.extensions.tasks.TasksExtension;
import org.junit.jupiter.api.Test;

/**
 * A {@link TaskConnector} may leave the legacy (pre-SEP-2663) {@code list} and {@code awaitResult}
 * hooks unset: {@code tasks/list} is then not served, while {@code tasks/result} still is, through
 * {@code get}. A dedicated server/connector is needed here because every other Tasks e2e test's
 * shared {@code TestTaskConnector} fixture always wires both.
 */
class TasksOptionalOperationsTest extends AbstractStatefulMcpE2eTest {

    @Override
    protected void startDefaultServer() {
        var minimalConnector = TaskConnector.builder()
                .get((context, request) -> {
                    throw new TaskNotFoundException(request.taskId());
                })
                .cancel((context, request) -> {})
                .update((context, request) -> {})
                .build();
        startServer(it -> it.withExtension(TasksExtension.class, t -> t.connector(minimalConnector)));
    }

    @Test
    void listIsNotFoundButResultIsServedThroughGetWhenConnectorLacksBothHooks() throws Exception {
        try (var client = createTestClient()) {
            client.initialize();

            var listJson = client.sendRpc("""
                    {"jsonrpc":"2.0","id":2,"method":"tasks/list","params":{}}
                    """);
            assertThatResponse(listJson).isJsonRpcError().hasErrorCode(-32601);

            var resultJson = client.sendRpc("""
                    {"jsonrpc":"2.0","id":3,"method":"tasks/result","params":{"taskId":"missing"}}
                    """);
            assertThatResponse(resultJson)
                    .isJsonRpcError()
                    .hasErrorCode(-32602)
                    .hasErrorMessage("Failed to retrieve task: Task not found");
        }
    }
}
