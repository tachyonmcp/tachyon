package dev.tachyonmcp.docs.extensions.tasks;

import dev.tachyonmcp.api.server.features.tasks.TaskConnector;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.api.server.features.tasks.TaskSupport;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.extensions.tasks.TasksExtension;
import java.time.Clock;

public final class TasksServer {

    private TasksServer() {}

    public static void main(String[] args) {
        var workflows = new Workflows();
        var clock = Clock.systemUTC();
        // snips-start: tasks_connector
        var tasks = TaskConnector.builder()
                .get((ctx, request) -> workflows.snapshot(request.taskId()))
                .cancel((ctx, request) -> workflows.cancel(request.taskId()))
                .update((ctx, request) -> workflows.submitInput(request.taskId(), request.inputResponses()))
                .build();

        var server = TachyonServer.builder()
                .withExtension(TasksExtension.class, t -> t.connector(tasks))
                .port(8080)
                .build();
        // snips-end: tasks_connector
        // snips-start: tasks_register_tool
        server.tools().register(
                tool -> tool.name("book_appointment").taskSupport(TaskSupport.REQUIRED),
                (context, request) -> {
                    var workflowId = workflows.start(request.arguments());
                    return ToolResult.task(
                            TaskSnapshot.working(workflowId, clock.instant(), 1));
                });
        // snips-end: tasks_register_tool
        server.start();
    }
}
