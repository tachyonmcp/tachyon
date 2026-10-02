package dev.tachyonmcp.docs.extensions.tasks;

import dev.tachyonmcp.api.server.domain.Args;
import dev.tachyonmcp.api.server.domain.TaskResult;
import dev.tachyonmcp.api.server.features.tasks.TaskConnector;
import dev.tachyonmcp.api.server.features.tasks.TaskNotFoundException;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.api.server.features.tasks.TaskState;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/** In-memory stand-in for the external workflow engine that owns task execution. */
public final class Workflows {

    public record Job(String taskId, @Nullable String sessionId, TaskSnapshot snapshot, Consumer<TaskSnapshot> onUpdate) {}

    private final Clock clock = Clock.systemUTC();
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final AtomicInteger ids = new AtomicInteger();
    private final AtomicInteger lookups = new AtomicInteger();

    public String start() {
        return start(snapshot -> {}, null);
    }

    public String start(Args arguments) {
        return start();
    }

    public String start(Args arguments, Consumer<TaskSnapshot> onUpdate) {
        return start(onUpdate, null);
    }

    public String start(Args arguments, @Nullable String sessionId) {
        return start(snapshot -> {}, sessionId);
    }

    private String start(Consumer<TaskSnapshot> onUpdate, @Nullable String sessionId) {
        var taskId = "wf-" + ids.incrementAndGet();
        var snapshot = TaskSnapshot.working(taskId, clock.instant(), 1);
        jobs.put(taskId, new Job(taskId, sessionId, snapshot, onUpdate));
        onUpdate.accept(snapshot);
        return taskId;
    }

    public @Nullable Job find(String taskId) {
        return jobs.get(taskId);
    }

    public TaskSnapshot snapshot(String taskId) throws TaskNotFoundException {
        lookups.incrementAndGet();
        return require(taskId).snapshot();
    }

    public void cancel(String taskId) throws TaskNotFoundException {
        var job = require(taskId);
        if (!job.snapshot().status().isTerminal()) {
            update(job, TaskState.CANCELLED, null);
        }
    }

    public void submitInput(String taskId, Map<String, Object> inputResponses) throws TaskNotFoundException {
        complete(taskId, "booking-" + taskId);
    }

    public void complete(String taskId, String bookingId) throws TaskNotFoundException {
        var job = require(taskId);
        if (!job.snapshot().status().isTerminal()) {
            update(job, TaskState.COMPLETED, TaskResult.completed(Map.of("bookingId", bookingId)));
        }
    }

    public int lookups() {
        return lookups.get();
    }

    public TaskConnector connector() {
        return TaskConnector.builder()
                .get((ctx, request) -> snapshot(request.taskId()))
                .cancel((ctx, request) -> cancel(request.taskId()))
                .update((ctx, request) -> submitInput(request.taskId(), request.inputResponses()))
                .build();
    }

    private Job require(String taskId) throws TaskNotFoundException {
        var job = jobs.get(taskId);
        if (job == null) {
            throw new TaskNotFoundException(taskId);
        }
        return job;
    }

    private void update(Job job, TaskState status, @Nullable TaskResult result) {
        var next = TaskSnapshot.builder()
                .from(job.snapshot())
                .status(status)
                .result(result)
                .lastUpdatedAt(clock.instant())
                .revision(job.snapshot().revision() + 1)
                .build();
        jobs.put(job.taskId(), new Job(job.taskId(), job.sessionId(), next, job.onUpdate()));
        job.onUpdate().accept(next);
    }
}
