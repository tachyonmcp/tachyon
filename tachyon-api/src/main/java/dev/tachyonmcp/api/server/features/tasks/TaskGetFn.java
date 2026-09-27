/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.features.tasks;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.api.runtime.InteractionContext;

/**
 * Returns the authoritative task snapshot for {@code tasks/get}.
 *
 * <p>The snapshot is the task's MCP status, which may differ from the state of the work behind it:
 * after an accepted {@link TaskCancelFn cancel}, legacy MCP 2025-11-25 requires {@code cancelled}
 * here, and to keep it, even while the work still runs or later finishes.
 */
@FunctionalInterface
@ExperimentalApi
public interface TaskGetFn {

    /**
     * Retrieves one task from the authoritative system.
     *
     * @param ctx current MCP interaction
     * @param request task lookup request
     * @return current task snapshot
     * @throws TaskNotFoundException when the task is unknown
     * @throws Exception when the authoritative system cannot serve the lookup
     */
    TaskSnapshot apply(InteractionContext ctx, TaskGetRequest request) throws Exception;
}
