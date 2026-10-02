package dev.tachyonmcp.docs.extensions.tasks;

import dev.tachyonmcp.api.server.features.tasks.TaskConnector;
import dev.tachyonmcp.api.server.features.tasks.TaskNotFoundException;
import java.util.Objects;

final class SessionScopedConnector {

    private SessionScopedConnector() {}

    static TaskConnector over(Workflows jobs) {
        return TaskConnector.builder()
                // snips-start: tasks_session_scoped_get
                .get((ctx, request) -> {
                    var job = jobs.find(request.taskId());
                    if (job == null || !Objects.equals(job.sessionId(), ctx.sessionId())) {
                        throw new TaskNotFoundException(request.taskId());
                    }
                    return job.snapshot();
                })
                // snips-end: tasks_session_scoped_get
                .cancel((ctx, request) -> jobs.cancel(request.taskId()))
                .update((ctx, request) -> jobs.submitInput(request.taskId(), request.inputResponses()))
                .build();
    }
}
