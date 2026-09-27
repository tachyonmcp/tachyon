/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.features.tasks;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.api.runtime.InteractionContext;

/**
 * Requests cancellation of externally executed work for {@code tasks/cancel}.
 *
 * <p>Cancellation is cooperative and may settle after {@link #apply} returns. Tachyon acknowledges
 * the request immediately; the caller observes the authoritative outcome through a later {@code
 * tasks/get}. For legacy MCP 2025-11-25 clients, which need the task {@code cancelled} before the
 * response, Tachyon then polls {@link TaskGetFn} until the task is terminal; implementations need not
 * wait themselves.
 *
 * <p>MCP 2025-11-25 separates the task's status from its execution: stopping the work is a
 * best effort, but once a cancel is accepted the task is {@code cancelled} and stays so, even if the
 * work later completes or fails. For those clients, record the accepted cancel where
 * {@link TaskGetFn} reads status and report {@code cancelled} from then on; the legacy response then
 * needs a single lookup. A system that reports only its execution state still works, answered once it
 * settles or when the configured legacy wait ends.
 */
@FunctionalInterface
@ExperimentalApi
public interface TaskCancelFn {

    /**
     * Signals cancellation intent to the authoritative system.
     *
     * @param ctx current MCP interaction
     * @param request task cancellation request
     * @throws TaskNotFoundException when the task is unknown
     * @throws Exception when cancellation cannot be requested
     */
    void apply(InteractionContext ctx, TaskCancelRequest request) throws Exception;
}
