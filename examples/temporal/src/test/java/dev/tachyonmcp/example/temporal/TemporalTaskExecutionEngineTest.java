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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
            worker.registerActivitiesImplementations((SlotAvailability) booking -> true);
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

    @Test
    @DisplayName("[TASK-05] a slot taken while the task waited sends it back to input_required instead of booking")
    void slotTakenWhileTheTaskWaitedSendsItBackToInputRequiredInsteadOfBooking() throws Exception {
        var checks = new AtomicInteger();
        try (var testEnvironment = TestWorkflowEnvironment.newInstance()) {
            var worker = testEnvironment.newWorker(TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(BookingWorkflowImpl.class);
            worker.registerActivitiesImplementations(new Slots(() -> checks.incrementAndGet() > 1));
            testEnvironment.start();
            var engine = BookingTaskEngine.create(testEnvironment.getWorkflowClient(), TASK_QUEUE);
            engine.start(CONTEXT, request("stale-slot"));
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(
                            engine.refresh(CONTEXT, get("stale-slot")).status())
                    .isEqualTo(TaskState.INPUT_REQUIRED));
            var firstPause = engine.refresh(CONTEXT, get("stale-slot"));
            assertThat(checks).as("nothing is checked before the caller approves").hasValue(0);

            engine.submitInput(CONTEXT, approval("stale-slot"));

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                var secondPause = engine.refresh(CONTEXT, get("stale-slot"));
                assertThat(secondPause.status()).isEqualTo(TaskState.INPUT_REQUIRED);
                assertThat(secondPause.statusMessage()).contains("no longer available");
                assertThat(secondPause.revision()).isGreaterThan(firstPause.revision());
                assertThat(secondPause.pendingInput()).isNotNull();
                assertThat(secondPause.result()).isNull();
            });
            assertThat(checks).hasValue(1);

            engine.submitInput(CONTEXT, approval("stale-slot"));

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                var terminal = engine.refresh(CONTEXT, get("stale-slot"));
                assertThat(terminal.status()).isEqualTo(TaskState.COMPLETED);
                assertThat(terminal.result()).isInstanceOf(TaskResult.Completed.class);
            });
            assertThat(checks).as("each approval is checked again").hasValue(2);
        }
    }

    @Test
    @DisplayName("[TASK-06] input is ignored while the task is not waiting for it")
    void inputIsIgnoredWhileTheTaskIsNotWaitingForIt() throws Exception {
        var checking = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var testEnvironment = TestWorkflowEnvironment.newInstance()) {
            var worker = testEnvironment.newWorker(TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(BookingWorkflowImpl.class);
            worker.registerActivitiesImplementations(new Slots(() -> {
                checking.countDown();
                try {
                    return release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }));
            testEnvironment.start();
            var engine = BookingTaskEngine.create(testEnvironment.getWorkflowClient(), TASK_QUEUE);
            engine.start(CONTEXT, request("busy"));
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(
                            engine.refresh(CONTEXT, get("busy")).status())
                    .isEqualTo(TaskState.INPUT_REQUIRED));
            engine.submitInput(CONTEXT, approval("busy"));
            assertThat(checking.await(5, TimeUnit.SECONDS)).isTrue();
            var working = engine.refresh(CONTEXT, get("busy"));
            assertThat(working.status()).isEqualTo(TaskState.WORKING);

            assertThatCode(() -> engine.submitInput(
                            CONTEXT,
                            TaskUpdateRequest.builder()
                                    .taskId("busy")
                                    .inputResponses(Map.of("approved", false))
                                    .build()))
                    .as("input nobody asked for is acknowledged, not an error")
                    .doesNotThrowAnyException();
            assertThat(engine.refresh(CONTEXT, get("busy")).revision())
                    .as("ignored input changes nothing")
                    .isEqualTo(working.revision());

            release.countDown();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(
                            engine.refresh(CONTEXT, get("busy")).status())
                    .as("the ignored 'approved: false' did not replace the accepted approval")
                    .isEqualTo(TaskState.COMPLETED));
        }
    }

    private record Slots(BooleanSupplier answer) implements SlotAvailability {
        @Override
        public boolean isAvailable(Map<String, Object> booking) {
            return answer.getAsBoolean();
        }
    }

    private static TaskUpdateRequest approval(String taskId) {
        return TaskUpdateRequest.builder()
                .taskId(taskId)
                .inputResponses(Map.of("approved", true))
                .build();
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
