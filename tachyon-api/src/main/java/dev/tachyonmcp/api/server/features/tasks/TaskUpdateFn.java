/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.features.tasks;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.api.runtime.InteractionContext;

/** Submits client input to externally executed work for {@code tasks/update}. */
@FunctionalInterface
@ExperimentalApi
public interface TaskUpdateFn {

    /**
     * Submits client input to the authoritative system.
     *
     * <p>The server checks only that the caller may reach the task. It does not know whether the
     * task is still waiting for input, or whether the business state it paused on still holds: a
     * task that reads healthy may rest on data that changed while it waited. The authoritative
     * system re-checks that state before any side effect, and refuses an unusable answer to an
     * outstanding request with {@link TaskInputRejectedException}.
     *
     * <p>An answer to a request that is no longer outstanding (already answered, superseded, or the
     * task has finished) is acknowledged and ignored, not refused: return normally.
     *
     * @param ctx current MCP interaction
     * @param request task input request
     * @throws TaskNotFoundException when the task is unknown
     * @throws TaskInputRejectedException when the input is refused; its message reaches the client
     * @throws Exception when the input cannot be submitted
     */
    void apply(InteractionContext ctx, TaskUpdateRequest request) throws Exception;
}
