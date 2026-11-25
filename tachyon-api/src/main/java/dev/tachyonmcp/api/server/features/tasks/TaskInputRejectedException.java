/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.features.tasks;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import java.util.Objects;

/**
 * Indicates that the authoritative task system refuses the input a client submitted with
 * {@code tasks/update}: the input is malformed, or the business state the task depends on has
 * changed since it paused. The task itself is left as it was.
 *
 * <p>An answer to a request that is no longer outstanding is not a reason to throw: SEP-2663 asks
 * servers to ignore those.
 *
 * <p>The message reaches the client as an invalid-params error, so it must not carry anything the
 * caller may not see.
 */
@ExperimentalApi
public final class TaskInputRejectedException extends Exception {

    /**
     * Task id.
     */
    private final String taskId;

    /**
     * Creates an exception for refused task input.
     *
     * @param taskId identifier of the task whose input is refused
     * @param message the reason, shown to the client
     */
    public TaskInputRejectedException(String taskId, String message) {
        super(Objects.requireNonNull(message, "message"));
        this.taskId = Objects.requireNonNull(taskId, "taskId");
    }

    /**
     * Returns the identifier of the task whose input is refused.
     *
     * @return the task identifier
     */
    public String taskId() {
        return taskId;
    }
}
