package dev.tachyonmcp.docs.extensions.tasks;

import dev.tachyonmcp.api.server.domain.TaskResult;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.api.server.features.tasks.TaskState;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.extensions.tasks.TasksExtension;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;

final class PublishSnapshots {

    private PublishSnapshots() {}

    static void publishWorking(TachyonServer server, String workflowId, Instant createdAt, Clock clock) {
        // snips-start: tasks_publish
        TasksExtension.tasks(server).publish(s -> s
                .taskId(workflowId)
                .status(TaskState.WORKING)
                .statusMessage("Charging card")
                .createdAt(createdAt)
                .lastUpdatedAt(clock.instant())
                .revision(4));
        // snips-end: tasks_publish
    }

    static void publishCompleted(TachyonServer server, TaskSnapshot previous, String bookingId, Clock clock) {
        // snips-start: tasks_publish_completed
        TasksExtension.tasks(server).publish(s -> s.next(previous)
                .status(TaskState.COMPLETED)
                .result(TaskResult.completed(Map.of("bookingId", bookingId)))
                .lastUpdatedAt(clock.instant()));
        // snips-end: tasks_publish_completed
    }

    static void reportProgress(TachyonServer server, String workflowId) {
        // snips-start: tasks_report_progress
        TasksExtension.tasks(server).reportProgress(workflowId, 40.0, 100.0, "Charging card");
        // snips-end: tasks_report_progress
    }
}
