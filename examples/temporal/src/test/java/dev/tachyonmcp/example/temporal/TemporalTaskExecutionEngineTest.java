/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.example.temporal;

import dev.tachyonmcp.api.json.JsonObject;
import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.domain.TaskResult;
import dev.tachyonmcp.api.server.features.tasks.TaskGetRequest;
import dev.tachyonmcp.api.server.features.tasks.TaskState;
import dev.tachyonmcp.api.server.features.tasks.TaskUpdateRequest;
import dev.tachyonmcp.tasks.temporal.TemporalTaskStartRequest;
import io.temporal.testing.TestWorkflowEnvironment;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

class TemporalTaskExecutionEngineTest {

    private static final String TASK_QUEUE = "tachyon-temporal-test";
    private static final InteractionContext CONTEXT = mock(InteractionContext.class, CALLS_REAL_METHODS);

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void startsRefreshesAndUpdatesWorkflowUsingTemporalTestEnvironment(boolean approved) throws Exception {
        try (var testEnvironment = TestWorkflowEnvironment.newInstance()) {
            var worker = testEnvironment.newWorker(TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(BookingWorkflowImpl.class);
            testEnvironment.start();
            var engine = BookingTaskEngine.create(testEnvironment.getWorkflowClient(), TASK_QUEUE);

            var started = engine.start(CONTEXT, request("test-workflow"));

            assertThat(started.taskId()).isEqualTo("test-workflow");
            assertThat(started.status()).isEqualTo(TaskState.WORKING);
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(
                            engine.refresh(CONTEXT, get("test-workflow")).status())
                    .isEqualTo(TaskState.INPUT_REQUIRED));
            var waiting = engine.refresh(CONTEXT, get("test-workflow"));
            assertThat(engine.refresh(CONTEXT, get("test-workflow")).lastUpdatedAt())
                    .isEqualTo(waiting.lastUpdatedAt());

            engine.submitInput(
                    CONTEXT,
                    TaskUpdateRequest.builder()
                            .taskId("test-workflow")
                            .inputResponses(Map.of("approved", approved))
                            .build());

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                var terminal = engine.refresh(CONTEXT, get("test-workflow"));
                assertThat(terminal.status()).isEqualTo(approved ? TaskState.COMPLETED : TaskState.REJECTED);
                // A rejected booking is still a completed task carrying a tool-level error — MCP's
                // "failed" status is reserved for genuine JSON-RPC protocol failures, not this.
                assertThat(terminal.result()).isInstanceOf(TaskResult.Completed.class);
            });
        }
    }

    private static TemporalTaskStartRequest request(String taskId) {
        return TemporalTaskStartRequest.builder()
                .taskId(taskId)
                .operation("book_appointment")
                .arguments(JsonObject.of(Map.of("customer", "Ada")))
                .build();
    }

    private static TaskGetRequest get(String taskId) {
        return TaskGetRequest.builder().taskId(taskId).build();
    }
}
