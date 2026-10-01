/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.features.tasks;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import java.util.Objects;

/** Indicates that an authoritative task system does not know the requested task identifier. */
@ExperimentalApi
public final class TaskNotFoundException extends Exception {

    /**
     * Task id.
     */
    private final String taskId;

    /**
     * Creates an exception for an unknown task.
     *
     * @param taskId unknown task identifier
     */
    public TaskNotFoundException(String taskId) {
        super("Task not found: " + Objects.requireNonNull(taskId, "taskId"));
        this.taskId = taskId;
    }

    /**
     * Creates an exception for an unknown task reported by an external system.
     *
     * @param taskId unknown task identifier
     * @param cause external-system failure
     */
    public TaskNotFoundException(String taskId, Throwable cause) {
        super("Task not found: " + Objects.requireNonNull(taskId, "taskId"), cause);
        this.taskId = taskId;
    }

    /**
     * Returns the unknown task identifier.
     *
     * @return the unknown task identifier
     */
    public String taskId() {
        return taskId;
    }
}
