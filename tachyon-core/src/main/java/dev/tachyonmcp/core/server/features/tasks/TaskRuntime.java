/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.features.tasks;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.server.domain.ProgressToken;
import dev.tachyonmcp.api.server.domain.ServerCapabilities;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.core.server.session.DispatchContext;
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
                DispatchContext context, TaskSnapshot snapshot, @Nullable ProgressToken progressToken) {
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
     * traffic to that call's session and binding the task to that call's caller; the context is the
     * request's own copy, so it is safe to use after an async tool left the dispatch thread. A task
     * the same caller published before the tool returned takes the route; one already routed keeps
     * its own.
     *
     * <p>The owner never changes. A task nobody owns, for example one a worker published through
     * {@code TachyonServer#tasks()} before the tool returned, cannot be claimed afterwards, and a task
     * owned by someone else cannot be taken over. Both are refused and nothing about them changes.
     *
     * @param context the context of the tool call, which fixes the session and the caller
     * @param snapshot the task the tool returned
     * @param progressToken the tool call's progress token, or {@code null} when it sent none
     * @return the effective snapshot
     * @throws IllegalStateException if the task already has a different owner
     */
    TaskSnapshot publish(DispatchContext context, TaskSnapshot snapshot, @Nullable ProgressToken progressToken);

    /**
     * Returns the {@code tasks} capability to advertise, or {@code null} to advertise none.
     *
     * @param hasTaskAugmentedTools whether a registered tool supports task augmentation
     */
    ServerCapabilities.@Nullable Tasks capability(boolean hasTaskAugmentedTools);
}
