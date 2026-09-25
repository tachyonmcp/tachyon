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
     * Publishes a newer revision of a task and returns the effective snapshot.
     *
     * <p>Only a task-augmented tool call that returns {@code ToolResult.task(...)} creates a cached
     * task, owned by that call's session for the task's lifetime. Publishing never changes the owner.
     * An owned task notifies only its owner, with {@code notifications/tasks/status}; an ownerless
     * task notifies only the {@code subscriptions/listen} subscribers of its id, with
     * {@code notifications/tasks}. A task Tachyon has not cached, e.g. one created on another node,
     * counts as ownerless and stays uncached. Status is never broadcast to other sessions.
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
