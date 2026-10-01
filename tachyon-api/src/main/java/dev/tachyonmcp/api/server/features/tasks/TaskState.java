/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.features.tasks;

import dev.tachyonmcp.api.annotations.ExperimentalApi;

/**
 * Task lifecycle states — superset of MCP 2025-11-25 + A2A.
 *
 * <p>Wire mapping (only the MCP-5 states are visible on the wire):
 * <pre>
 *   SUBMITTED      → "working"      (internal pre-state, never exposed)
 *   REJECTED       → "completed" with an error tool result
 *   AUTH_REQUIRED  → unsupported by MCP
 *   WORKING        → "working"
 *   INPUT_REQUIRED → "input_required"
 *   COMPLETED      → "completed"
 *   FAILED         → "failed"
 *   CANCELLED      → "cancelled"
 *   UNKNOWN        → unsupported by MCP
 * </pre>
 *
 * <p>See <a href="https://github.com/modelcontextprotocol/modelcontextprotocol/pull/2663">SEP-2663</a>
 * (and <a href="https://modelcontextprotocol.io/seps/1686-tasks">SEP-1686</a> for MCP 2025-11-25) and
 * A2A <a href="https://a2a-protocol.org/latest/specification/#413-taskstate">Task State</a>
 */
@ExperimentalApi
public enum TaskState {
    /**
     * Submitted.
     */
    SUBMITTED(false),
    /**
     * Rejected.
     */
    REJECTED(true),
    /**
     * Indicates that authentication is required to proceed. This is an interrupted state.
     */
    AUTH_REQUIRED(false),
    /**
     * Working.
     */
    WORKING(false),
    /**
     * Input required.
     */
    INPUT_REQUIRED(false),
    /**
     * Completed.
     */
    COMPLETED(true),
    /**
     * Failed.
     */
    FAILED(true),
    /**
     * Cancelled.
     */
    CANCELLED(true),
    /**
     * Unknown.
     */
    UNKNOWN(true);
    private final boolean terminal;

    TaskState(boolean terminal) {
        this.terminal = terminal;
    }

    /**
     * Reports whether this state is terminal.
     *
     * @return {@code true} for COMPLETED, FAILED, CANCELLED, REJECTED, or UNKNOWN
     */
    public boolean isTerminal() {
        return terminal;
    }
}
