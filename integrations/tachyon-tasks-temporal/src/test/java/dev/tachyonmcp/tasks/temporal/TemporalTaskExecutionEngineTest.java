/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.tasks.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

import dev.tachyonmcp.api.json.JsonObject;
import dev.tachyonmcp.api.json.JsonSchema;
import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.domain.FormInputRequest;
import dev.tachyonmcp.api.server.domain.InputRequestBundle;
import dev.tachyonmcp.api.server.domain.TaskResult;
import dev.tachyonmcp.api.server.features.tasks.TaskCancelRequest;
import dev.tachyonmcp.api.server.features.tasks.TaskGetRequest;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.api.server.features.tasks.TaskState;
import dev.tachyonmcp.api.server.features.tasks.TaskUpdateRequest;
import io.temporal.client.WorkflowFailedException;
import io.temporal.failure.CanceledFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TemporalTaskExecutionEngineTest {

    private static final String TASK_QUEUE = "tachyon-tasks-temporal-test";
    private static final InteractionContext CONTEXT = mock(InteractionContext.class);

    @Test
    void reattachesToWorkflowAndForwardsInput() throws Exception {
        try (var environment = TestWorkflowEnvironment.newInstance()) {
            var worker = environment.newWorker(TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(TestWorkflowImpl.class);
            environment.start();
            var engine = engine(environment);

            var started = engine.start(CONTEXT, request("workflow-1"));

            assertThat(started.status()).isEqualTo(TaskState.WORKING);
            var reattachedEngine = engine(environment);
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(reattachedEngine
                                    .refresh(CONTEXT, get("workflow-1"))
                                    .status())
                            .isEqualTo(TaskState.INPUT_REQUIRED));

            reattachedEngine.submitInput(
                    CONTEXT,
                    TaskUpdateRequest.builder()
                            .taskId("workflow-1")
                            .inputResponses(Map.of("approved", true))
                            .build());

            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(reattachedEngine
                                    .refresh(CONTEXT, get("workflow-1"))
                                    .status())
                            .isEqualTo(TaskState.COMPLETED));
        }
    }

    @Test
    void requestsWorkflowCancellationWithoutSynthesizingTaskState() throws Exception {
        try (var environment = TestWorkflowEnvironment.newInstance()) {
            var worker = environment.newWorker(TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(TestWorkflowImpl.class);
            environment.start();
            var engine = engine(environment);
            engine.start(CONTEXT, request("workflow-cancel"));

            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(engine.refresh(CONTEXT, get("workflow-cancel"))
                                    .status())
                            .isEqualTo(TaskState.INPUT_REQUIRED));

            engine.cancel(
                    CONTEXT,
                    TaskCancelRequest.builder().taskId("workflow-cancel").build());

            var workflow = environment.getWorkflowClient().newUntypedWorkflowStub("workflow-cancel");
            assertThatThrownBy(() -> workflow.getResult(Void.class))
                    .isInstanceOf(WorkflowFailedException.class)
                    .hasCauseInstanceOf(CanceledFailure.class);
        }
    }

    private static TemporalTaskExecutionEngine engine(TestWorkflowEnvironment environment) {
        return TemporalTaskExecutionEngine.builder(environment.getWorkflowClient())
                .taskQueue(TASK_QUEUE)
                .route(TemporalTaskRoute.builder(TestStatus.class)
                        .operation("test_operation")
                        .workflowType("TestWorkflow")
                        .startArguments(
                                request -> new Object[] {request.arguments().asMap()})
                        .statusQuery("taskStatus")
                        .snapshotMapper(TemporalTaskExecutionEngineTest::snapshot)
                        .inputUpdate("provideInput", input -> new Object[] {input.inputResponses()})
                        .build())
                .clock(Clock.systemUTC())
                .build();
    }

    private static TaskSnapshot snapshot(String taskId, TestStatus status) {
        return TaskSnapshot.builder()
                .taskId(taskId)
                .status(status.state())
                .statusMessage(status.message())
                .createdAt(status.createdAt())
                .lastUpdatedAt(status.updatedAt())
                .result(status.state() == TaskState.COMPLETED ? TaskResult.completed(status.result()) : null)
                .pendingInput(status.state() == TaskState.INPUT_REQUIRED ? pendingInput() : null)
                .revision(status.revision())
                .build();
    }

    private static InputRequestBundle pendingInput() {
        return new InputRequestBundle(
                Map.of("field", FormInputRequest.of("test", JsonSchema.unchecked("{\"type\":\"object\"}"))), null);
    }

    private static TemporalTaskStartRequest request(String taskId) {
        return TemporalTaskStartRequest.builder()
                .taskId(taskId)
                .operation("test_operation")
                .arguments(JsonObject.of(Map.of("customer", "Ada")))
                .build();
    }

    private static TaskGetRequest get(String taskId) {
        return TaskGetRequest.builder().taskId(taskId).build();
    }

    @WorkflowInterface
    @SuppressWarnings("unused")
    public interface TestWorkflow {

        @WorkflowMethod
        void run(Map<String, Object> arguments);

        @QueryMethod
        TestStatus taskStatus();

        @UpdateMethod
        void provideInput(Map<String, Object> input);
    }

    public static final class TestWorkflowImpl implements TestWorkflow {

        private Instant createdAt;
        private TaskState state = TaskState.SUBMITTED;
        private Map<String, Object> input;
        private long revision;

        @Override
        public void run(Map<String, Object> arguments) {
            createdAt = now();
            state = TaskState.INPUT_REQUIRED;
            revision++;
            Workflow.await(() -> input != null);
            state = TaskState.COMPLETED;
            revision++;
        }

        @Override
        public TestStatus taskStatus() {
            return new TestStatus(state, state.name(), createdAt, now(), input == null ? Map.of() : input, revision);
        }

        @Override
        public void provideInput(Map<String, Object> input) {
            this.input = input;
            revision++;
        }

        private static Instant now() {
            return Instant.ofEpochMilli(Workflow.currentTimeMillis());
        }
    }

    record TestStatus(
            TaskState state,
            String message,
            Instant createdAt,
            Instant updatedAt,
            Map<String, Object> result,
            long revision) {}
}
