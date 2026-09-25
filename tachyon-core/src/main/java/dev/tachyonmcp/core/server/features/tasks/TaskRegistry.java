/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.features.tasks;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.server.domain.ProgressToken;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.api.server.features.tasks.Tasks;
import org.jspecify.annotations.Nullable;

/** Internal server task projection cache. */
@InternalApi
public interface TaskRegistry extends Tasks {

    /** Returns whether an external task execution connector is configured. */
    boolean executionConfigured();

    /**
     * Creates the task a task-augmented tool call returned. Its {@code sessionId} and
     * {@code progressToken} are fixed for the task's lifetime; they are explicit since an async tool
     * completes off the dispatch thread. Only this method caches a task. Idempotent for the same
     * owner: an existing entry then just takes a newer revision.
     *
     * @return the effective snapshot, or {@code null} when another session owns the task id: the
     *     owner's cached snapshot stays unchanged
     */
    @Nullable
    TaskSnapshot create(TaskSnapshot snapshot, @Nullable String sessionId, @Nullable ProgressToken progressToken);
}
