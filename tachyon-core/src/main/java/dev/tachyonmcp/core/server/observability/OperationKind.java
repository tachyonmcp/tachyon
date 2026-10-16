/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.observability;

import dev.tachyonmcp.api.annotations.InternalApi;

/** The shape of an inbound MCP operation being observed. */
@InternalApi
public enum OperationKind {
    /** An ordinary JSON-RPC request (tools/resources/prompts/etc.). */
    REQUEST,
    /** A JSON-RPC notification (no response expected). */
    NOTIFICATION,
    /** The {@code initialize} handshake request. */
    INITIALIZE,
    /**
     * The authentication of a request's HTTP head, observed only when it rejects the caller. It happens
     * before any JSON-RPC message is read, so there is no MCP method: {@link OperationInfo#method()} is
     * {@link OperationInfo#AUTHENTICATION_METHOD} and the outcome is always {@link
     * OperationOutcome.Rejected} with the HTTP status.
     */
    AUTHENTICATION
}
