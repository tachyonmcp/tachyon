/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.features.tasks;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.server.domain.ProgressToken;
import org.jspecify.annotations.Nullable;

/**
 * Where a task's push traffic goes: the session and progress token of the task-augmented tool call
 * that returned it. A delivery address only, never an access decision: any caller holding the task
 * id reaches the task, and the {@code TaskConnector} authorizes it.
 *
 * @param sessionId the calling session, or {@code null} on a stateless server
 * @param progressToken the tool call's progress token, or {@code null} when it sent none
 */
@InternalApi
public record TaskRoute(
        @Nullable String sessionId, @Nullable ProgressToken progressToken) {

    /** No route: status goes to {@code subscriptions/listen} subscribers only, progress nowhere. */
    public static final TaskRoute NONE = new TaskRoute(null, null);
}
