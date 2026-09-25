/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.tasks.temporal;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.features.tasks.TaskCancelRequest;
import dev.tachyonmcp.api.server.features.tasks.TaskConnector;
import dev.tachyonmcp.api.server.features.tasks.TaskGetRequest;
import dev.tachyonmcp.api.server.features.tasks.TaskNotFoundException;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.api.server.features.tasks.TaskUpdateRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Task execution methods backed by one Temporal Workflow Execution per MCP task.
 *
 * <p>Use {@link #connector()} to wire the engine into Tachyon's Tasks capability.
 */
@ExperimentalApi
public final class TemporalTaskExecutionEngine {

    private static final Logger logger = LoggerFactory.getLogger(TemporalTaskExecutionEngine.class);

    private final WorkflowClient workflowClient;
    private final String taskQueue;
    private final Clock clock;
    private final Map<String, TemporalTaskRoute<?>> routesByOperation;
    private final Map<String, TemporalTaskRoute<?>> routesByWorkflowType;

    private TemporalTaskExecutionEngine(Builder builder) {
        workflowClient = Objects.requireNonNull(builder.workflowClient, "workflowClient");
        taskQueue = requireText(builder.taskQueue, "taskQueue");
        clock = Objects.requireNonNull(builder.clock, "clock");
        if (builder.routesByOperation.isEmpty()) {
            throw new IllegalStateException("At least one Temporal task route is required");
        }
        routesByOperation = Map.copyOf(builder.routesByOperation);
        routesByWorkflowType = Map.copyOf(builder.routesByWorkflowType);
    }

    /**
     * Creates an engine builder.
     *
     * @param workflowClient Temporal client used to start and address Workflows
     * @return a new builder
     */
    public static Builder builder(WorkflowClient workflowClient) {
        return new Builder(workflowClient);
    }

    /**
     * Creates the Tachyon connector for this engine.
     *
     * @return connector with all modern Tasks operations wired
     */
    public TaskConnector connector() {
        return TaskConnector.builder()
                .get(this::refresh)
                .cancel(this::cancel)
                .update(this::submitInput)
                .build();
    }

    /**
     * Starts the routed Temporal Workflow and returns its initial MCP projection.
     *
     * @param context current MCP interaction
     * @param request operation, stable Workflow ID, arguments, and metadata
     * @return initial authoritative task snapshot
     */
    public TaskSnapshot start(InteractionContext context, TemporalTaskStartRequest request) {
        var route = routeForOperation(request.operation());
        logger.info(
                "Starting Temporal workflow: taskId={}, operation={}, workflowType={}, taskQueue={}",
                request.taskId(),
                request.operation(),
                route.workflowType(),
                taskQueue);
        var workflow = workflowClient.newUntypedWorkflowStub(
                route.workflowType(),
                WorkflowOptions.newBuilder()
                        .setWorkflowId(request.taskId())
                        .setTaskQueue(taskQueue)
                        .build());
        workflow.start(route.startArguments(request));
        return TaskSnapshot.working(request.taskId(), clock.instant(), 1);
    }

    /**
     * Queries the authoritative Workflow status.
     *
     * @param context current MCP interaction
     * @param request task lookup request
     * @return current task snapshot
     * @throws TaskNotFoundException when Temporal does not know the Workflow ID
     */
    public TaskSnapshot refresh(InteractionContext context, TaskGetRequest request) throws TaskNotFoundException {
        try {
            var workflow = workflow(request.taskId());
            var snapshot = routeFor(workflow).query(workflow, request.taskId());
            logger.debug(
                    "Refreshed Temporal workflow: taskId={}, state={}, revision={}",
                    request.taskId(),
                    snapshot.status(),
                    snapshot.revision());
            return snapshot;
        } catch (WorkflowNotFoundException e) {
            throw new TaskNotFoundException(request.taskId(), e);
        }
    }

    /**
     * Requests cooperative Workflow cancellation.
     *
     * @param context current MCP interaction
     * @param request task cancellation request
     * @throws TaskNotFoundException when Temporal does not know the Workflow ID
     */
    public void cancel(InteractionContext context, TaskCancelRequest request) throws TaskNotFoundException {
        try {
            var workflow = workflow(request.taskId());
            logger.info("Requesting Temporal workflow cancellation: taskId={}", request.taskId());
            workflow.cancel();
        } catch (WorkflowNotFoundException e) {
            throw new TaskNotFoundException(request.taskId(), e);
        }
    }

    /**
     * Submits MCP task input through the configured Temporal Update.
     *
     * @param context current MCP interaction
     * @param input submitted task input
     * @throws TaskNotFoundException when Temporal does not know the Workflow ID
     */
    public void submitInput(InteractionContext context, TaskUpdateRequest input) throws TaskNotFoundException {
        try {
            var workflow = workflow(input.taskId());
            logger.info(
                    "Submitting input to Temporal workflow: taskId={}, fields={}",
                    input.taskId(),
                    input.inputResponses().keySet());
            routeFor(workflow).submitInput(workflow, input);
        } catch (WorkflowNotFoundException e) {
            throw new TaskNotFoundException(input.taskId(), e);
        }
    }

    private WorkflowStub workflow(String taskId) {
        return workflowClient.newUntypedWorkflowStub(taskId);
    }

    private TemporalTaskRoute<?> routeForOperation(String operation) {
        var route = routesByOperation.get(operation);
        if (route == null) {
            throw new IllegalArgumentException("No Temporal route for operation: " + operation);
        }
        return route;
    }

    private TemporalTaskRoute<?> routeFor(WorkflowStub workflow) {
        var workflowType = workflow.describe().getWorkflowType();
        var route = routesByWorkflowType.get(workflowType);
        if (route == null) {
            throw new IllegalStateException("No Temporal task route for Workflow type: " + workflowType);
        }
        return route;
    }

    private static String requireText(@Nullable String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must not be blank");
        }
        return value;
    }

    /** Builder for {@link TemporalTaskExecutionEngine}. */
    public static final class Builder {

        private final WorkflowClient workflowClient;
        private final Map<String, TemporalTaskRoute<?>> routesByOperation = new LinkedHashMap<>();
        private final Map<String, TemporalTaskRoute<?>> routesByWorkflowType = new LinkedHashMap<>();
        private String taskQueue;
        private Clock clock = Clock.systemUTC();

        private Builder(WorkflowClient workflowClient) {
            this.workflowClient = Objects.requireNonNull(workflowClient, "workflowClient");
        }

        /**
         * Sets the task queue used when starting Workflows.
         *
         * @param taskQueue worker task queue
         * @return this builder
         */
        public Builder taskQueue(String taskQueue) {
            this.taskQueue = taskQueue;
            return this;
        }

        /**
         * Registers one operation-to-Workflow route.
         *
         * @param route route to register
         * @return this builder
         */
        public Builder route(TemporalTaskRoute<?> route) {
            Objects.requireNonNull(route, "route");
            putUnique(routesByOperation, route.operation(), route, "operation");
            putUnique(routesByWorkflowType, route.workflowType(), route, "Workflow type");
            return this;
        }

        /**
         * Sets the clock used for snapshot timestamps.
         *
         * @param clock clock to use
         * @return this builder
         */
        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        /**
         * Builds the Temporal task execution engine.
         *
         * @return configured engine
         */
        public TemporalTaskExecutionEngine build() {
            return new TemporalTaskExecutionEngine(this);
        }

        private static void putUnique(
                Map<String, TemporalTaskRoute<?>> routes, String key, TemporalTaskRoute<?> route, String keyType) {
            if (routes.putIfAbsent(key, route) != null) {
                throw new IllegalArgumentException("Duplicate Temporal task route " + keyType + ": " + key);
            }
        }
    }
}
