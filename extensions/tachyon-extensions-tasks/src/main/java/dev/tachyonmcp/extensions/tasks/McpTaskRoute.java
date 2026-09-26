/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.tasks;

import dev.tachyonmcp.api.server.domain.ProgressToken;
import dev.tachyonmcp.extensions.tasks.engine.TaskRoute;
import org.jspecify.annotations.Nullable;

/**
 * MCP push address of a task: the session and progress token of the task-augmented tool call that
 * returned it. A delivery address only, never an access decision: any caller holding the task id
 * reaches the task, and the {@code TaskConnector} authorizes it.
 *
 * @param sessionId the calling session, or {@code null} on a stateless server
 * @param progressToken the tool call's progress token, or {@code null} when it sent none
 */
record McpTaskRoute(@Nullable String sessionId, @Nullable ProgressToken progressToken) implements TaskRoute {

    /** Returns the route for a tool call, or {@link TaskRoute#NONE} when it carries no address. */
    static TaskRoute of(@Nullable String sessionId, @Nullable ProgressToken progressToken) {
        return sessionId == null && progressToken == null ? TaskRoute.NONE : new McpTaskRoute(sessionId, progressToken);
    }
}
