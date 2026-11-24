/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.example.temporal;

import dev.tachyonmcp.api.json.JsonSchema;
import dev.tachyonmcp.api.server.domain.FormInputRequest;
import dev.tachyonmcp.api.server.domain.InputRequestBundle;
import dev.tachyonmcp.api.server.domain.TaskResult;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.api.server.features.tasks.TaskState;
import dev.tachyonmcp.tasks.temporal.TemporalTaskExecutionEngine;
import dev.tachyonmcp.tasks.temporal.TemporalTaskRoute;
import io.temporal.client.WorkflowClient;
import java.util.Map;

/** Configures the booking application's Temporal task engine. */
public final class BookingTaskEngine {

    private BookingTaskEngine() {}

    /**
     * Creates the task engine and maps booking workflow status to MCP task snapshots.
     *
     * @param workflowClient Temporal client used to address booking Workflows
     * @param taskQueue worker task queue
     * @return configured Temporal task engine
     */
    public static TemporalTaskExecutionEngine create(WorkflowClient workflowClient, String taskQueue) {
        var route = TemporalTaskRoute.builder(TemporalTaskStatus.class)
                .operation("book_appointment")
                .workflowType("BookingWorkflow")
                .startArguments(request -> new Object[] {request.arguments().asMap()})
                .statusQuery("taskStatus")
                .snapshotMapper(BookingTaskEngine::snapshot)
                .inputUpdate("provideInput", input -> new Object[] {input.inputResponses()})
                .build();
        return TemporalTaskExecutionEngine.builder(workflowClient)
                .taskQueue(taskQueue)
                .route(route)
                .build();
    }

    private static TaskSnapshot snapshot(String taskId, TemporalTaskStatus status) {
        var result = switch (status.state()) {
            case COMPLETED -> TaskResult.completed(status.result());
            case REJECTED -> TaskResult.completedWithError(status.message());
            default -> null;
        };
        var pendingInput = status.state() == TaskState.INPUT_REQUIRED ? approvalRequest(status.revision()) : null;
        return TaskSnapshot.builder()
                .taskId(taskId)
                .status(status.state())
                .statusMessage(status.message())
                .createdAt(status.createdAt())
                .lastUpdatedAt(status.updatedAt())
                .result(result)
                .pendingInput(pendingInput)
                .revision(status.revision())
                .build();
    }

    private static InputRequestBundle approvalRequest(long revision) {
        var schema = JsonSchema.unchecked(
                "{\"type\":\"object\",\"properties\":{\"approved\":{\"type\":\"boolean\"}}}");
        return new InputRequestBundle(Map.of(BookingWorkflow.approvalKey(revision), FormInputRequest.of("Approve booking", schema)), null);
    }
}
