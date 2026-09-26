/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.tasks.engine;

import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import org.jspecify.annotations.Nullable;

/**
 * Push traffic of a {@link TaskEngine}, which a protocol binding encodes and delivers. Callbacks run
 * on the publishing thread, status ones under the task's ordering lock: never block in them.
 */
public interface TaskEvents {

    /**
     * A task reached a newer revision. Called at most once per revision, in revision order.
     *
     * @param snapshot the new revision
     * @param route the task's route, or {@link TaskRoute#NONE}
     */
    default void onStatus(TaskSnapshot snapshot, TaskRoute route) {}

    /**
     * Progress was reported for a cached task.
     *
     * @param taskId the task
     * @param route the task's route, or {@link TaskRoute#NONE}
     * @param progress the progress so far
     * @param total the total, or {@code null} when unknown
     * @param message a human-readable message, or {@code null}
     */
    default void onProgress(
            String taskId, TaskRoute route, double progress, @Nullable Double total, @Nullable String message) {}
}
