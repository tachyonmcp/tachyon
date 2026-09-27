/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.features.tasks;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.api.annotations.LegacyApi;
import dev.tachyonmcp.api.runtime.InteractionContext;

/**
 * Waits through the external system until an authoritative terminal projection is available, for
 * the legacy (pre-SEP-2663) blocking {@code tasks/result}.
 *
 * <p>May block in the external client's supported wait operation. Optional: without it, Tachyon
 * serves {@code tasks/result} by calling {@link TaskGetFn} until the task is terminal, sleeping for
 * the snapshot's {@code pollInterval} (else a configured default) between calls, bounded by the
 * task's {@code ttl} and a configured maximum wait. Set it when the external system offers a cheaper
 * wait than repeated lookups; it then owns its wait bound.
 */
@FunctionalInterface
@ExperimentalApi
@LegacyApi
public interface TaskAwaitResultFn {

    /**
     * Waits for one authoritative terminal snapshot.
     *
     * @param ctx current MCP interaction
     * @param request legacy result request
     * @return terminal task snapshot
     * @throws TaskNotFoundException when the task is unknown
     * @throws Exception when the authoritative system cannot await the result
     */
    TaskSnapshot apply(InteractionContext ctx, TaskAwaitResultRequest request) throws Exception;
}
