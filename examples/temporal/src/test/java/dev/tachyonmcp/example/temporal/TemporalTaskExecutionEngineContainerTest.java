/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.example.temporal;

import dev.tachyonmcp.api.json.JsonObject;
import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.features.tasks.TaskGetRequest;
import dev.tachyonmcp.api.server.features.tasks.TaskState;
import dev.tachyonmcp.api.server.features.tasks.TaskUpdateRequest;
import dev.tachyonmcp.tasks.temporal.TemporalTaskExecutionEngine;
import dev.tachyonmcp.tasks.temporal.TemporalTaskStartRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.WorkerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

@Testcontainers(disabledWithoutDocker = true)
class TemporalTaskExecutionEngineContainerTest {

    private static final String TASK_QUEUE = "tachyon-temporal-container-test";
    private static final InteractionContext CONTEXT = mock(InteractionContext.class, CALLS_REAL_METHODS);

    @Container
    private static final GenericContainer<?> TEMPORAL = new GenericContainer<>(
                    DockerImageName.parse("temporalio/temporal:1.8.2"))
            .withCommand("server", "start-dev", "--ip", "0.0.0.0", "--headless")
            .withExposedPorts(7233)
            .waitingFor(Wait.forListeningPort())
            .withStartupTimeout(Duration.ofMinutes(2));

    private static WorkflowServiceStubs service;
    private static WorkerFactory workerFactory;
    private static TemporalTaskExecutionEngine engine;

    @BeforeAll
    static void startWorker() {
        var target = TEMPORAL.getHost() + ":" + TEMPORAL.getMappedPort(7233);
        service = WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder().setTarget(target).build());
        var workflowClient = WorkflowClient.newInstance(service);
        workerFactory = WorkerFactory.newInstance(workflowClient);
        var worker = workerFactory.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(BookingWorkflowImpl.class);
        workerFactory.start();
        engine = BookingTaskEngine.create(workflowClient, TASK_QUEUE);
    }

    @AfterAll
    static void stopWorker() {
        if (workerFactory != null) {
            workerFactory.shutdown();
        }
        if (service != null) {
            service.shutdown();
        }
    }

    @Test
    void runsTaskLifecycleAgainstRealTemporalServer() throws Exception {
        var taskId = "container-workflow";

        engine.start(CONTEXT, request(taskId));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(
                        engine.refresh(CONTEXT, get(taskId)).status())
                .isEqualTo(TaskState.INPUT_REQUIRED));
        engine.submitInput(
                CONTEXT,
                TaskUpdateRequest.builder()
                        .taskId(taskId)
                        .inputResponses(Map.of("approved", true))
                        .build());
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(
                        engine.refresh(CONTEXT, get(taskId)).status())
                .isEqualTo(TaskState.COMPLETED));
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
