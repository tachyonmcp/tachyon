/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.conformance.v2026_07_28;

import dev.tachyonmcp.api.json.JsonSchema;
import dev.tachyonmcp.api.server.domain.InputRequest;
import dev.tachyonmcp.api.server.domain.InputRequestBundle;
import dev.tachyonmcp.api.server.domain.ServerError;
import dev.tachyonmcp.api.server.domain.TaskResult;
import dev.tachyonmcp.api.server.features.tasks.TaskConnector;
import dev.tachyonmcp.api.server.features.tasks.TaskNotFoundException;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.api.server.features.tasks.TaskState;
import dev.tachyonmcp.api.server.features.tasks.TaskSupport;
import dev.tachyonmcp.api.server.features.tasks.TaskUpdateRequest;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.ServerBuilder;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.extensions.skills.ClasspathSkillsRegistry;
import dev.tachyonmcp.extensions.skills.SkillsExtension;
import dev.tachyonmcp.extensions.tasks.TasksExtension;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

/**
 * The 2026-07-28 server plus the tasks and skills extensions, with the fixtures upstream's
 * {@code [extension]} scenarios call ({@code src/scenarios/server/tasks/*.ts}).
 */
public class ExtensionConformanceServer extends ConformanceServer {

    // language=json
    private static final JsonSchema NAME_SCHEMA = parseJson("""
        {"type": "object", "properties": {"name": {"type": "string"}}}
        """);

    // language=json
    private static final JsonSchema SECONDS_SCHEMA = parseJson("""
        {"type": "object", "properties": {"seconds": {"type": "number"}}}
        """);

    private final Map<String, TaskSnapshot> tasks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());

    @Override
    protected ServerBuilder builder(boolean isStateful) {
        var connector = TaskConnector.builder()
                .get((ctx, request) -> require(request.taskId()))
                .cancel((ctx, request) ->
                        settle(require(request.taskId()).taskId(), s -> s.status(TaskState.CANCELLED)))
                .update((ctx, request) -> answer(request))
                .build();
        return super.builder(isStateful)
                .withExtension(TasksExtension.class, t -> t.connector(connector))
                .withExtension(SkillsExtension.class, s -> s.registry(new ClasspathSkillsRegistry("skills")));
    }

    @Override
    protected void registerVersionSpecificTools(ServerEngine server) {
        super.registerVersionSpecificTools(server);
        server.tools()
                .register(
                        t -> t.name("greet").description("Sync-only greeting").inputSchema(NAME_SCHEMA),
                        (ctx, request) -> ToolResult.text("Hello, "
                                + request.arguments().stringOpt("name").orElse("World") + "!"))
                .register(
                        t -> t.name("slow_compute")
                                .description("Sleeps `seconds`, then answers")
                                .inputSchema(SECONDS_SCHEMA)
                                .taskSupport(TaskSupport.OPTIONAL),
                        (ctx, request) -> {
                            var millis = (long)
                                    (request.arguments().doubleOpt("seconds").orElse(1) * 1000);
                            var answer = ToolResult.text("Computed after " + millis + " ms");
                            if (!ctx.isExtensionEnabled(TasksExtension.ID)) {
                                delay(millis);
                                return answer;
                            }
                            return start(
                                    millis, s -> s.status(TaskState.COMPLETED).result(TaskResult.completed(answer)));
                        })
                .register(
                        t -> t.name("failing_job")
                                .description("Task that ends in a tool error")
                                .inputSchema(ANY_ARGS_SCHEMA)
                                .taskSupport(TaskSupport.REQUIRED),
                        (ctx, request) -> start(
                                1000,
                                s -> s.status(TaskState.COMPLETED).result(TaskResult.completedWithError("Job failed"))))
                .register(
                        t -> t.name("protocol_error_job")
                                .description("Task that ends in a protocol error")
                                .inputSchema(ANY_ARGS_SCHEMA)
                                .taskSupport(TaskSupport.REQUIRED),
                        (ctx, request) -> start(
                                100,
                                s -> s.status(TaskState.FAILED)
                                        .result(TaskResult.failed(new ServerError(
                                                ServerError.Kind.INTERNAL_ERROR, "Job crashed", null)))))
                .register(
                        t -> t.name("confirm_delete")
                                .description("Task that asks for one confirmation")
                                .inputSchema(ANY_ARGS_SCHEMA)
                                .taskSupport(TaskSupport.REQUIRED),
                        (ctx, request) ->
                                park(Map.of("confirm", buildFormElicitation("Delete the file?", "confirm", "boolean"))))
                .register(
                        t -> t.name("multi_input")
                                .description("Task that asks for two inputs at once")
                                .inputSchema(ANY_ARGS_SCHEMA)
                                .taskSupport(TaskSupport.REQUIRED),
                        (ctx, request) -> park(Map.of(
                                "first", buildFormElicitation("First value?", "value", "string"),
                                "second", buildFormElicitation("Second value?", "value", "string"))))
                .register(
                        t -> t.name("test_tool_with_task")
                                .description("Gathers user_name by MRTR, then runs as a task")
                                .inputSchema(ANY_ARGS_SCHEMA)
                                .taskSupport(TaskSupport.REQUIRED),
                        (ctx, request) -> {
                            var responses = request.inputResponses();
                            var response = responses != null ? responses.get("user_name") : null;
                            if (response == null) {
                                return ToolResult.inputRequired(
                                        Map.of(
                                                "user_name",
                                                buildFormElicitation("What is your name?", "name", "string")),
                                        null);
                            }
                            var name = String.valueOf(field(field(response, "content"), "name"));
                            return start(
                                    100,
                                    s -> s.status(TaskState.COMPLETED)
                                            .result(TaskResult.completed(ToolResult.text("Hello, " + name + "!"))));
                        });
    }

    /** Starts a working task that {@code finish} settles after {@code millis}, unless it is cancelled first. */
    private ToolResult start(long millis, UnaryOperator<TaskSnapshot.Builder> finish) {
        var snapshot = TaskSnapshot.working(UUID.randomUUID().toString(), Instant.now(), 1);
        tasks.put(snapshot.taskId(), snapshot);
        scheduler.schedule(() -> settle(snapshot.taskId(), finish), millis, TimeUnit.MILLISECONDS);
        return ToolResult.task(snapshot);
    }

    /** Starts a task parked on {@code inputs}; it completes once {@code tasks/update} answers them all. */
    private ToolResult park(Map<String, ? extends InputRequest> inputs) {
        var now = Instant.now();
        var snapshot = TaskSnapshot.inputRequired(
                UUID.randomUUID().toString(), now, now, 1, new InputRequestBundle(inputs, null));
        tasks.put(snapshot.taskId(), snapshot);
        return ToolResult.task(snapshot);
    }

    private void answer(TaskUpdateRequest request) throws TaskNotFoundException {
        var current = require(request.taskId());
        var pending = current.pendingInput();
        if (pending == null) return;
        var remaining = new HashMap<>(pending.inputRequests());
        remaining.keySet().removeAll(request.inputResponses().keySet());
        settle(
                current.taskId(),
                s -> remaining.isEmpty()
                        ? s.status(TaskState.COMPLETED)
                                .pendingInput(null)
                                .result(TaskResult.completed(ToolResult.text(
                                        "Received " + request.inputResponses().keySet())))
                        : s.pendingInput(new InputRequestBundle(remaining, null)));
    }

    /** Applies {@code change} to a task that is not terminal yet. */
    private void settle(String taskId, UnaryOperator<TaskSnapshot.Builder> change) {
        tasks.computeIfPresent(
                taskId,
                (id, current) -> current.status().isTerminal()
                        ? current
                        : change.apply(TaskSnapshot.builder()
                                        .from(current)
                                        .lastUpdatedAt(Instant.now())
                                        .revision(current.revision() + 1))
                                .build());
    }

    private TaskSnapshot require(String taskId) throws TaskNotFoundException {
        var snapshot = tasks.get(taskId);
        if (snapshot == null) throw new TaskNotFoundException(taskId);
        return snapshot;
    }
}
