/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.example.temporal;

import dev.tachyonmcp.api.json.JsonObject;
import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.domain.TaskResult;
import dev.tachyonmcp.api.server.features.tasks.TaskGetRequest;
import dev.tachyonmcp.api.server.features.tasks.TaskState;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.api.server.features.tasks.TaskUpdateRequest;
import dev.tachyonmcp.tasks.temporal.TemporalTaskExecutionEngine;
import dev.tachyonmcp.tasks.temporal.TemporalTaskStartRequest;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.Workflow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

            engine.submitInput(CONTEXT, answer(waiting, approved));

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

            engine.submitInput(CONTEXT, answer(firstPause, true));

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                var secondPause = engine.refresh(CONTEXT, get("stale-slot"));
                assertThat(secondPause.status()).isEqualTo(TaskState.INPUT_REQUIRED);
                assertThat(secondPause.statusMessage()).contains("no longer available");
                assertThat(secondPause.revision()).isGreaterThan(firstPause.revision());
                assertThat(secondPause.pendingInput()).isNotNull();
                assertThat(secondPause.pendingInput().inputRequests().keySet())
                        .as("SEP-2663: a request key is never reused after its answer was delivered")
                        .hasSize(1)
                        .doesNotContainAnyElementsOf(
                                firstPause.pendingInput().inputRequests().keySet());
                assertThat(secondPause.result()).isNull();
            });
            assertThat(checks).hasValue(1);

            engine.submitInput(CONTEXT, answer(firstPause, true));
            assertThat(engine.refresh(CONTEXT, get("stale-slot")).status())
                    .as("an answer under the first, already answered key is ignored")
                    .isEqualTo(TaskState.INPUT_REQUIRED);
            engine.submitInput(CONTEXT, answer(engine.refresh(CONTEXT, get("stale-slot")), true));

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
            var asked = engine.refresh(CONTEXT, get("busy"));
            engine.submitInput(CONTEXT, answer(asked, true));
            assertThat(checking.await(5, TimeUnit.SECONDS)).isTrue();
            var working = engine.refresh(CONTEXT, get("busy"));
            assertThat(working.status()).isEqualTo(TaskState.WORKING);

            assertThatCode(() -> engine.submitInput(CONTEXT, answer(asked, false)))
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

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void bookingFinishedBeforeKeyedAnswersStillReplays(boolean approved) throws Exception {
        var upgraded = new AtomicBoolean();
        try (var testEnvironment = TestWorkflowEnvironment.newInstance()) {
            startWorker(testEnvironment, upgraded);
            var engine = BookingTaskEngine.create(testEnvironment.getWorkflowClient(), TASK_QUEUE);
            var expected = approved ? TaskState.COMPLETED : TaskState.REJECTED;
            engine.start(CONTEXT, request("legacy-finished"));
            awaitStatus(engine, "legacy-finished", TaskState.INPUT_REQUIRED);
            engine.submitInput(
                    CONTEXT,
                    TaskUpdateRequest.builder()
                            .taskId("legacy-finished")
                            .inputResponses(Map.of("approved", approved))
                            .build());
            awaitStatus(engine, "legacy-finished", expected);
            var before = engine.refresh(CONTEXT, get("legacy-finished"));

            upgraded.set(true);

            assertThat(engine.refresh(CONTEXT, get("legacy-finished"))).isEqualTo(before);
        }
    }

    @Test
    void bookingPausedBeforeKeyedAnswersTakesTheKeyedAnswerItNowAsksFor() throws Exception {
        var upgraded = new AtomicBoolean();
        try (var testEnvironment = TestWorkflowEnvironment.newInstance()) {
            startWorker(testEnvironment, upgraded);
            var engine = BookingTaskEngine.create(testEnvironment.getWorkflowClient(), TASK_QUEUE);
            engine.start(CONTEXT, request("legacy-paused"));
            awaitStatus(engine, "legacy-paused", TaskState.INPUT_REQUIRED);

            upgraded.set(true);

            var asked = engine.refresh(CONTEXT, get("legacy-paused"));
            engine.submitInput(
                    CONTEXT,
                    TaskUpdateRequest.builder()
                            .taskId("legacy-paused")
                            .inputResponses(Map.of("approved", false))
                            .build());
            assertThat(engine.refresh(CONTEXT, get("legacy-paused")))
                    .as("a flat answer sent after the upgrade answers nothing")
                    .isEqualTo(asked);
            engine.submitInput(CONTEXT, answer(asked, true));
            awaitStatus(engine, "legacy-paused", TaskState.COMPLETED);
        }
    }

    /**
     * Starts a worker that runs the legacy Workflow until {@code upgraded} is set. It has no sticky
     * queue, so every Workflow task and query replays the recorded history.
     */
    private static void startWorker(TestWorkflowEnvironment environment, AtomicBoolean upgraded) {
        var worker = environment.newWorker(
                TASK_QUEUE,
                WorkerOptions.newBuilder()
                        .setStickyQueueScheduleToStartTimeout(Duration.ZERO)
                        .build());
        worker.registerWorkflowImplementationFactory(
                BookingWorkflow.class,
                () -> upgraded.get() ? new BookingWorkflowImpl() : new LegacyBookingWorkflow());
        worker.registerActivitiesImplementations((SlotAvailability) booking -> true);
        environment.start();
    }

    private static void awaitStatus(TemporalTaskExecutionEngine engine, String taskId, TaskState expected) {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(
                        engine.refresh(CONTEXT, get(taskId)).status())
                .isEqualTo(expected));
    }

    /** The booking Workflow as released before keyed answers: flat input, no slot check. */
    public static final class LegacyBookingWorkflow implements BookingWorkflow {

        private Instant createdAt;
        private TaskState state = TaskState.SUBMITTED;
        private String message = "Submitted";
        private Map<String, Object> result = Map.of();
        private Map<String, Object> input;
        private long revision;
        private Instant observedAt = Instant.EPOCH;

        @Override
        public void book(Map<String, Object> arguments) {
            createdAt = now();
            observedAt = createdAt;
            transitionTo(TaskState.WORKING, "Booking appointment");
            transitionTo(TaskState.INPUT_REQUIRED, "Approval required");
            Workflow.await(() -> input != null);
            var approved = Boolean.TRUE.equals(input.get("approved"));
            result = Map.of("booking", arguments, "confirmed", approved);
            if (approved) {
                transitionTo(TaskState.COMPLETED, "Appointment booked");
            } else {
                transitionTo(TaskState.REJECTED, "Booking rejected");
            }
        }

        @Override
        public TemporalTaskStatus taskStatus() {
            return new TemporalTaskStatus(state, message, createdAt, observedAt, result, revision);
        }

        @Override
        public void provideInput(Map<String, Object> input) {
            this.input = input;
            transitionTo(TaskState.WORKING, "Input accepted: " + input.keySet());
        }

        private void transitionTo(TaskState nextState, String nextMessage) {
            state = nextState;
            message = nextMessage;
            revision++;
            observedAt = now();
        }

        private static Instant now() {
            return Instant.ofEpochMilli(Workflow.currentTimeMillis());
        }
    }

    private record Slots(BooleanSupplier answer) implements SlotAvailability {
        @Override
        public boolean isAvailable(Map<String, Object> booking) {
            return answer.getAsBoolean();
        }
    }

    /** Answers the request {@code asked} carries, in the SEP-2663 elicitation response shape. */
    private static TaskUpdateRequest answer(TaskSnapshot asked, boolean approved) {
        var key = asked.pendingInput().inputRequests().keySet().iterator().next();
        return TaskUpdateRequest.builder()
                .taskId(asked.taskId())
                .inputResponses(Map.of(key, Map.of("action", "accept", "content", Map.of("approved", approved))))
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
