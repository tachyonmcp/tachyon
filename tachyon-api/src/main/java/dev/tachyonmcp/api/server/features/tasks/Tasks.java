/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.features.tasks;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import org.jspecify.annotations.Nullable;

/**
 * Façade interface for MCP tasks
 */
@ExperimentalApi
public interface Tasks {
    /**
     * Publishes a newer revision of a task and returns the effective snapshot. A task Tachyon has not
     * cached is cached; an older or equal revision leaves the cache unchanged.
     *
     * <p>Status goes to the session of the task-augmented tool call that returned the task, with
     * {@code notifications/tasks/status}, and to every {@code subscriptions/listen} subscriber of its
     * id that the {@link TaskConnector} let read it when the stream opened, with
     * {@code notifications/tasks}. Publishing never picks a session from the calling thread, and
     * status is never broadcast to other sessions.
     */
    TaskSnapshot publish(TaskSnapshot snapshot);

    /** Returns the cached task projection, or {@code null} when absent. */
    @Nullable
    TaskSnapshot get(String taskId);

    /**
     * Removes a cached task projection without changing externally owned work.
     *
     * @return {@code true} if a task with this id existed and was removed
     */
    boolean remove(String taskId);

    /**
     * Reports progress for a task-augmented tool call, emitted as {@code notifications/progress}
     * to the progress token of the task-augmented tool call that created the task.
     *
     * <p>A no-op, logged at debug, when {@code taskId} is not cached or that call carried no progress
     * token; {@link TaskSnapshot} carries none.
     *
     * @param taskId   the task to report progress for
     * @param progress the current progress value
     * @param total    the total expected value, or {@code null} if unknown
     * @param message  optional progress message
     */
    void reportProgress(String taskId, double progress, @Nullable Double total, @Nullable String message);
}
