/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.observability;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.server.domain.ServerError;
import org.jspecify.annotations.Nullable;

/**
 * The terminal fact reported for one {@link OperationInfo}, resolved by the dispatcher from
 * classification it already computed (a {@link ServerError}'s resolved wire code, a task
 * registry publish, etc.) — never re-derived by the listener.
 */
@InternalApi
public sealed interface OperationOutcome {

    /**
     * Rejected before any handler ran (unknown method, bad session state, malformed params, ...).
     * {@code wireCode} is the JSON-RPC error code and is only meaningful when {@code error} is
     * non-null; a transport-level rejection with no JSON-RPC envelope (e.g. a missing session
     * header) reports {@code error == null} and leaves it {@code 0}.
     */
    record Rejected(@Nullable ServerError error, int httpStatus, int wireCode) implements OperationOutcome {}

    /**
     * The handler ran and its result was encoded onto the wire. Any captured response content lives
     * on {@link OperationInfo#responsePayload()}, mirroring {@link OperationInfo#requestPayload()} —
     * not here, since it's known before the outcome is.
     */
    record Completed() implements OperationOutcome {}

    /**
     * A {@code tools/call} returned a domain-level payload failure ({@code ToolResult.error(...)})
     * rather than throwing — still a JSON-RPC success (the client sees {@code isError: true} in the
     * result, not an error envelope), but distinct from an ordinary {@link Completed} outcome so a
     * listener can classify it without inspecting response content. Captured response content, if
     * any, lives on {@link OperationInfo#responsePayload()}.
     */
    record PayloadFailure() implements OperationOutcome {}

    /**
     * The handler produced a result but encoding it failed; the client received a fallback error.
     * {@code causeType} (the throwable's class name) is always available for classification;
     * {@code cause} is the throwable itself, present only when the server's opt-in exception-detail
     * capture policy is enabled.
     */
    record SerializationFailed(String causeType, @Nullable Throwable cause) implements OperationOutcome {}

    /**
     * The operation ended in a JSON-RPC error after a handler ran. {@code cause} is the throwable
     * that produced it, or {@code null} when the handler returned a {@link ServerError} value
     * directly rather than throwing/failing, or when a real throwable exists but the server's
     * opt-in exception-detail capture policy is disabled.
     */
    record HandlerFailed(
            ServerError error, int wireCode, @Nullable Throwable cause) implements OperationOutcome {}

    /** The handler's work was cancelled (client-initiated {@code notifications/cancelled} or similar). */
    record Cancelled() implements OperationOutcome {}

    /** A {@code tools/call} was handed off to an out-of-band task rather than returning inline. */
    record TaskHandoff(String taskId) implements OperationOutcome {}

    /** A recognized notification was handled ({@code notifications/initialized}, {@code .../cancelled}). */
    record NotificationAccepted() implements OperationOutcome {}

    /** A notification with no matching handler was silently accepted per protocol. */
    record NotificationIgnored() implements OperationOutcome {}

    /**
     * A {@code subscriptions/listen} SSE stream ended in a genuine transport failure rather than an
     * ordinary client disconnect or server shutdown (both of which report {@link Cancelled} or
     * {@link Completed}). No wire code: the connection is already gone, so nothing is sent back.
     */
    record StreamFailed(Throwable cause) implements OperationOutcome {}
}
