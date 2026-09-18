/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.observability;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.api.annotations.InternalApi;

/**
 * A passive, read-only observer of the MCP dispatch lifecycle: {@code start} then exactly one
 * {@code complete}, per operation. Structurally incapable of short-circuiting, rejecting, or
 * substituting a handler's result — there is no {@code proceed()}/{@code reject()} here.
 *
 * <p>Registered listeners never block an async handler stage: the dispatcher only ever attaches a
 * listener's returned {@link ObservationScope} around synchronous work (decode, kicking off a
 * handler's async work, encoding a completed result) and reattaches it explicitly across executor
 * hops — it never joins an in-flight {@code CompletionStage} to keep a scope open.
 *
 * <p>A throwing listener never affects handler execution, the response sent to the client, or any
 * other registered listener — every call into a listener is fault-isolated by the dispatcher.
 */
@InternalApi
@ExperimentalApi
public interface ObservationListener {

    /**
     * Fires once, as early as {@code method} (and {@code id}, if any) is known, before any handler or
     * rejection. Listeners are called in registration order, so the {@link ObservationScope} a
     * listener returns here nests inside the scope of every listener registered before it.
     */
    ObservationScope start(OperationInfo info);

    /** Fires exactly once, at the operation's terminal boundary — see the observability design notes for what that boundary is per operation kind. */
    void complete(OperationInfo info, OperationOutcome outcome);
}
