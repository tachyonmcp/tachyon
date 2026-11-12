/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.tasks.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.tachyonmcp.api.json.JsonObject;
import dev.tachyonmcp.api.json.JsonSchema;
import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.domain.FormInputRequest;
import dev.tachyonmcp.api.server.domain.InputRequestBundle;
import dev.tachyonmcp.api.server.domain.TaskResult;
import dev.tachyonmcp.api.server.features.tasks.TaskCancelRequest;
import dev.tachyonmcp.api.server.features.tasks.TaskGetRequest;
import dev.tachyonmcp.api.server.features.tasks.TaskNotFoundException;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.api.server.features.tasks.TaskState;
import dev.tachyonmcp.api.server.features.tasks.TaskUpdateRequest;
import dev.tachyonmcp.api.server.security.SecurityContext;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.ThrowingConsumer;

class TemporalTaskExecutionEngineTest {

    private static final String TASK_QUEUE = "tachyon-tasks-temporal-test";
    private static final InteractionContext CONTEXT = caller(SecurityContext.anonymous());
    private static final InteractionContext ALICE = caller(SecurityContext.authenticated(() -> "alice", List.of()));
    private static final InteractionContext BOB = caller(SecurityContext.authenticated(() -> "bob", List.of()));
    private static final InteractionContext ALICE_OF_TENANT_ONE =
            caller(SecurityContext.authenticated(() -> "alice", List.of(), null, "tenant-1|alice"));
    private static final InteractionContext ALICE_OF_TENANT_TWO =
            caller(SecurityContext.authenticated(() -> "alice", List.of(), null, "tenant-2|alice"));

    private static InteractionContext caller(SecurityContext securityContext) {
        var context = mock(InteractionContext.class);
        when(context.securityContext()).thenReturn(securityContext);
        return context;
    }

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

    /**
     * 2025-11-25 Tasks § Security: receivers MUST bind tasks to the authorization context. The owner
     * travels with the Workflow, so an engine that never saw the start (another node, a restart)
     * still refuses everyone else, and answers as for an unknown id. The owner is the caller's owner
     * key, not its principal name: two tenants' Alices are two owners.
     */
    @Test
    void aWorkflowBelongsToTheCallerThatStartedItOnEveryEngine() throws Exception {
        try (var environment = TestWorkflowEnvironment.newInstance()) {
            var worker = environment.newWorker(TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(TestWorkflowImpl.class);
            environment.start();
            var started = engine(environment).start(ALICE_OF_TENANT_ONE, request("workflow-owned"));
            assertThat(started.ownerKey())
                    .as("the started task carries its owner for the server to cache")
                    .isEqualTo("tenant-1|alice");
            var otherNode = engine(environment);
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(otherNode
                                    .refresh(ALICE_OF_TENANT_ONE, get("workflow-owned"))
                                    .status())
                            .isEqualTo(TaskState.INPUT_REQUIRED));
            assertThat(environment
                            .getWorkflowClient()
                            .newUntypedWorkflowStub("workflow-owned")
                            .describe()
                            .getMemo(TemporalTaskExecutionEngine.OWNER_MEMO, String.class))
                    .isEqualTo("tenant-1|alice");
            assertThat(otherNode
                            .refresh(ALICE_OF_TENANT_ONE, get("workflow-owned"))
                            .ownerKey())
                    .as("another node reads the owner from the memo")
                    .isEqualTo("tenant-1|alice");

            for (var stranger : List.of(BOB, CONTEXT, ALICE, ALICE_OF_TENANT_TWO)) {
                assertHidden(otherNode, stranger, "workflow-owned");
            }

            assertThat(otherNode
                            .refresh(ALICE_OF_TENANT_ONE, get("workflow-owned"))
                            .status())
                    .as("neither cancelled nor given input by a stranger")
                    .isEqualTo(TaskState.INPUT_REQUIRED);
            otherNode.submitInput(ALICE_OF_TENANT_ONE, input("workflow-owned"));
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(otherNode
                                    .refresh(ALICE_OF_TENANT_ONE, get("workflow-owned"))
                                    .status())
                            .isEqualTo(TaskState.COMPLETED));
        }
    }

    /** A Workflow an anonymous caller started has no owner: access to it is the application's decision. */
    @Test
    void aWorkflowStartedAnonymouslyIsOpenToEveryCaller() throws Exception {
        try (var environment = TestWorkflowEnvironment.newInstance()) {
            var worker = environment.newWorker(TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(TestWorkflowImpl.class);
            environment.start();
            var engine = engine(environment);
            engine.start(CONTEXT, request("workflow-open"));

            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(
                                    engine.refresh(ALICE, get("workflow-open")).status())
                            .isEqualTo(TaskState.INPUT_REQUIRED));
            engine.cancel(
                    BOB, TaskCancelRequest.builder().taskId("workflow-open").build());

            var workflow = environment.getWorkflowClient().newUntypedWorkflowStub("workflow-open");
            assertThat(workflow.describe().getMemo(TemporalTaskExecutionEngine.OWNER_MEMO, String.class))
                    .as("an empty memo marks it unowned by design")
                    .isEqualTo("");
            assertThat(engine.refresh(ALICE, get("workflow-open")).ownerKey())
                    .as("nobody owns it")
                    .isNull();
            assertThatThrownBy(() -> workflow.getResult(Void.class))
                    .isInstanceOf(WorkflowFailedException.class)
                    .hasCauseInstanceOf(CanceledFailure.class);
        }
    }

    /**
     * A Workflow without the owner memo (started before the engine wrote it, or outside the engine)
     * is not open: every request is authorized by {@code unownedAccess}, which refuses all by default.
     */
    @Test
    void aWorkflowWithoutOwnerMemoIsReachedOnlyByCallersUnownedAccessAdmits() throws Exception {
        try (var environment = TestWorkflowEnvironment.newInstance()) {
            var worker = environment.newWorker(TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(TestWorkflowImpl.class);
            environment.start();
            var client = environment.getWorkflowClient();
            client.newUntypedWorkflowStub(
                            "TestWorkflow",
                            WorkflowOptions.newBuilder()
                                    .setWorkflowId("workflow-legacy")
                                    .setTaskQueue(TASK_QUEUE)
                                    .build())
                    .start(Map.of("customer", "Ada"));
            var byDefault = engine(client);
            var admitsAlice = builder(client)
                    .unownedAccess((context, taskId) -> context == ALICE)
                    .build();

            for (var caller : List.of(ALICE, BOB, CONTEXT)) {
                assertHidden(byDefault, caller, "workflow-legacy");
            }
            for (var stranger : List.of(BOB, CONTEXT)) {
                assertHidden(admitsAlice, stranger, "workflow-legacy");
            }

            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(admitsAlice
                                    .refresh(ALICE, get("workflow-legacy"))
                                    .status())
                            .as("neither cancelled nor given input by a refused caller")
                            .isEqualTo(TaskState.INPUT_REQUIRED));
            assertThat(admitsAlice.refresh(ALICE, get("workflow-legacy")).ownerKey())
                    .as("a Workflow without the memo has no owner: admitting a caller never makes it theirs")
                    .isNull();
            admitsAlice.submitInput(ALICE, input("workflow-legacy"));
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(admitsAlice
                                    .refresh(ALICE, get("workflow-legacy"))
                                    .status())
                            .isEqualTo(TaskState.COMPLETED));
        }
    }

    /**
     * A Workflow ID names a run only until that run closes. Ownership is read from the run {@code
     * describe} returned, so whatever follows must stay on that run: when another caller takes the ID
     * over in between, the previous owner may at most meet her own closed run, never the new one.
     */
    @Test
    @DisplayName("A run that replaced the described one is not queried by the previous owner")
    void aReplacedRunIsNotQueriedByThePreviousOwner() {
        assertReplacedRunIsUnreachable(
                "workflow-replaced-query",
                engine -> assertThat(engine.refresh(ALICE, get("workflow-replaced-query"))
                                .statusMessage())
                        .as("the run Alice owned, not Bob's")
                        .contains("Alice")
                        .doesNotContain("Bob"));
    }

    @Test
    @DisplayName("A run that replaced the described one is not given input by the previous owner")
    void aReplacedRunIsNotGivenInputByThePreviousOwner() {
        assertReplacedRunIsUnreachable(
                "workflow-replaced-input",
                engine -> engine.submitInput(
                        ALICE,
                        TaskUpdateRequest.builder()
                                .taskId("workflow-replaced-input")
                                .inputResponses(Map.of("approved", true))
                                .build()));
    }

    @Test
    @DisplayName("A run that replaced the described one is not cancelled by the previous owner")
    void aReplacedRunIsNotCancelledByThePreviousOwner() {
        assertReplacedRunIsUnreachable(
                "workflow-replaced-cancel",
                engine -> engine.cancel(
                        ALICE,
                        TaskCancelRequest.builder()
                                .taskId("workflow-replaced-cancel")
                                .build()));
    }

    private static void assertReplacedRunIsUnreachable(
            String taskId, ThrowingConsumer<TemporalTaskExecutionEngine> aliceOperation) {
        try (var environment = TestWorkflowEnvironment.newInstance()) {
            var worker = environment.newWorker(TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(TestWorkflowImpl.class);
            environment.start();
            var client = environment.getWorkflowClient();
            var plain = engine(client);
            plain.start(ALICE, request(taskId, "Alice"));
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(
                            () -> assertThat(plain.refresh(ALICE, get(taskId)).status())
                                    .isEqualTo(TaskState.INPUT_REQUIRED));
            var alice = engine(afterDescribe(client, () -> {
                client.newUntypedWorkflowStub(taskId).terminate("replaced");
                plain.start(BOB, request(taskId, "Bob"));
            }));

            var thrown = catchThrowable(() -> aliceOperation.accept(alice));

            if (thrown != null) {
                assertThat(thrown).isInstanceOf(TaskNotFoundException.class);
            }
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                var bobs = plain.refresh(BOB, get(taskId));
                assertThat(bobs.statusMessage())
                        .as("Bob's run replaced Alice's")
                        .contains("Bob");
                assertThat(bobs.status()).as("Bob's run was given no input").isEqualTo(TaskState.INPUT_REQUIRED);
            });
            await().during(Duration.ofMillis(500))
                    .atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(client.newUntypedWorkflowStub(taskId)
                                    .describe()
                                    .getStatus())
                            .as("Bob's run was not cancelled")
                            .isEqualTo(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING));
        }
    }

    /** A client whose stubs run {@code action} right after {@code describe} returns, before anything else. */
    private static WorkflowClient afterDescribe(WorkflowClient real, Runnable action) {
        var client = mock(WorkflowClient.class, delegatesTo(real));
        doAnswer(call -> {
                    var realStub = real.newUntypedWorkflowStub(call.<String>getArgument(0));
                    var stub = mock(WorkflowStub.class, delegatesTo(realStub));
                    doAnswer(describe -> {
                                var description = realStub.describe();
                                action.run();
                                return description;
                            })
                            .when(stub)
                            .describe();
                    return stub;
                })
                .when(client)
                .newUntypedWorkflowStub(anyString());
        return client;
    }

    private static TemporalTaskExecutionEngine engine(TestWorkflowEnvironment environment) {
        return engine(environment.getWorkflowClient());
    }

    private static TemporalTaskExecutionEngine engine(WorkflowClient client) {
        return builder(client).build();
    }

    private static TemporalTaskExecutionEngine.Builder builder(WorkflowClient client) {
        return TemporalTaskExecutionEngine.builder(client)
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
                .clock(Clock.systemUTC());
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
        return request(taskId, "Ada");
    }

    private static TemporalTaskStartRequest request(String taskId, String customer) {
        return TemporalTaskStartRequest.builder()
                .taskId(taskId)
                .operation("test_operation")
                .arguments(JsonObject.of(Map.of("customer", customer)))
                .build();
    }

    private static void assertHidden(TemporalTaskExecutionEngine engine, InteractionContext caller, String taskId) {
        assertThatThrownBy(() -> engine.refresh(caller, get(taskId)))
                .isInstanceOf(TaskNotFoundException.class)
                .hasMessage(new TaskNotFoundException(taskId).getMessage());
        assertThatThrownBy(() -> engine.submitInput(caller, input(taskId))).isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> engine.cancel(
                        caller, TaskCancelRequest.builder().taskId(taskId).build()))
                .isInstanceOf(TaskNotFoundException.class);
    }

    private static TaskUpdateRequest input(String taskId) {
        return TaskUpdateRequest.builder()
                .taskId(taskId)
                .inputResponses(Map.of("approved", true))
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
        private Object customer;
        private TaskState state = TaskState.SUBMITTED;
        private Map<String, Object> input;
        private long revision;

        @Override
        public void run(Map<String, Object> arguments) {
            createdAt = now();
            customer = arguments.get("customer");
            state = TaskState.INPUT_REQUIRED;
            revision++;
            Workflow.await(() -> input != null);
            state = TaskState.COMPLETED;
            revision++;
        }

        @Override
        public TestStatus taskStatus() {
            return new TestStatus(
                    state,
                    state.name() + " for " + customer,
                    createdAt,
                    now(),
                    input == null ? Map.of() : input,
                    revision);
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
