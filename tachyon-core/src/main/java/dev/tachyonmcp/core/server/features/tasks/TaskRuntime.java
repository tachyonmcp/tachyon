/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.features.tasks;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.server.domain.ProgressToken;
import dev.tachyonmcp.api.server.domain.ServerCapabilities;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import org.jspecify.annotations.Nullable;

/**
 * What core needs from a tasks implementation: the seam between the tool handlers and the
 * tasks extension that owns the runtime. An extension installs one through
 * {@code ServerEngine#installTaskRuntime}; until then {@link #NONE} answers.
 */
@InternalApi
public interface TaskRuntime {

    /** No tasks extension installed: nothing is configured or advertised. */
    TaskRuntime NONE = new TaskRuntime() {
        @Override
        public boolean executionConfigured() {
            return false;
        }

        @Override
        public TaskSnapshot publish(
                TaskSnapshot snapshot, @Nullable String sessionId, @Nullable ProgressToken progressToken) {
            return snapshot;
        }

        @Override
        public ServerCapabilities.@Nullable Tasks capability(boolean hasTaskAugmentedTools) {
            return null;
        }
    };

    /** Returns whether an external task execution connector is configured. */
    boolean executionConfigured();

    /**
     * Caches {@code snapshot} for the task a task-augmented tool call returned, routing its push
     * traffic to that call; the route is explicit since an async tool completes off the dispatch
     * thread. An entry published before the tool returned takes the route; one already routed keeps
     * its own. Never refuses: access is the connector's decision, not the cache's.
     *
     * @param snapshot the task the tool returned
     * @param sessionId the calling session, or {@code null} on a stateless server
     * @param progressToken the tool call's progress token, or {@code null} when it sent none
     * @return the effective snapshot
     */
    TaskSnapshot publish(TaskSnapshot snapshot, @Nullable String sessionId, @Nullable ProgressToken progressToken);

    /**
     * Returns the {@code tasks} capability to advertise, or {@code null} to advertise none.
     *
     * @param hasTaskAugmentedTools whether a registered tool supports task augmentation
     */
    ServerCapabilities.@Nullable Tasks capability(boolean hasTaskAugmentedTools);
}
