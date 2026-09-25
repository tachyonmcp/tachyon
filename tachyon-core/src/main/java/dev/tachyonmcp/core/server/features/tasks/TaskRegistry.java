/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.features.tasks;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.api.server.features.tasks.Tasks;
import java.util.Set;

/** Internal server task projection cache. */
@InternalApi
public interface TaskRegistry extends Tasks {

    /** Returns whether an external task execution connector is configured. */
    boolean executionConfigured();

    /**
     * {@link #publish(TaskSnapshot)} for the task a task-augmented tool call returned, routing its
     * push traffic to that call; {@code route} is explicit since an async tool completes off the
     * dispatch thread. An entry published before the tool returned takes the route; one already
     * routed keeps its own. Never refuses: access is the connector's decision, not the cache's.
     *
     * @return the effective snapshot
     */
    TaskSnapshot publish(TaskSnapshot snapshot, TaskRoute route);

    /**
     * Returns the ids of {@code taskIds} that {@code ctx} may read, as the connector's {@code get}
     * decides: the check behind a {@code subscriptions/listen} stream's task ids. Fails closed: an id
     * the connector refuses, fails on, or cannot serve (no connector) is left out. Caches nothing.
     * Blocks on the connector, so never call it on an event loop.
     */
    Set<String> readableTaskIds(InteractionContext ctx, Set<String> taskIds);
}
