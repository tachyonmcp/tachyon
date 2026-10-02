package dev.tachyonmcp.docs.extensions.tasks;

import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.api.server.features.tasks.TaskSupport;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.server.config.SessionConfig;
import dev.tachyonmcp.extensions.tasks.TasksExtension;
import java.time.Clock;

final class ContextFacade {

    private ContextFacade() {}

    static TachyonServer start(Workflows workflows, Clock clock) {
        var server = TachyonServer.builder()
                .port(0)
                .session(SessionConfig.Builder::enabled)
                .withExtension(TasksExtension.class, t -> t.connector(workflows.connector()))
                .build();
        server.tools().register(
                tool -> tool.name("book").taskSupport(TaskSupport.REQUIRED),
                // snips-start: tasks_ctx_facade
                (ctx, request) -> {
                    var tasks = TasksExtension.tasks(ctx);
                    var workflowId = workflows.start(request.arguments(), snapshot -> tasks.publish(snapshot));
                    return ToolResult.task(TaskSnapshot.working(workflowId, clock.instant(), 1));
                }
                // snips-end: tasks_ctx_facade
                );
        server.start();
        return server;
    }
}
