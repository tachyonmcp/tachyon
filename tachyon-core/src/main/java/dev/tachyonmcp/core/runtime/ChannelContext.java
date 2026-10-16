/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.runtime;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.core.protocol.Protocol;
import org.jspecify.annotations.Nullable;

/**
 * Context for an MCP channel, providing access to the protocol, session, and lifecycle. It is
 * connection-scoped state (on a stateful 2025-11-25 connection one instance serves every request), so
 * it never holds a caller: {@link #securityContext()} throws rather than answer anonymous. The caller
 * of a request is fixed, immutably, in the request's {@code DispatchContext}.
 */
@InternalApi
public interface ChannelContext extends InteractionContext {

    /**
     * Returns the MCP protocol version negotiated for this channel.
     *
     * @return the protocol
     */
    Protocol protocol();

    @Override
    default String protocolVersion() {
        return protocol().versionString();
    }

    /**
     * Returns the current session, or {@code null} if not yet established.
     *
     * @return the session, or {@code null}
     */
    @Nullable
    Session session();

    @Override
    default @Nullable String sessionId() {
        var session = session();
        return session == null ? null : session.id();
    }

    /**
     * Sets the lifecycle state for this channel.
     *
     * @param lifecycle the new lifecycle state
     */
    void setLifecycle(Lifecycle lifecycle);

    /**
     * Sets the session for this channel.
     *
     * @param session the session, or {@code null} to clear
     */
    void setSession(@Nullable Session session);

    /**
     * Enables an extension for this channel.
     *
     * @param extensionId the extension identifier
     */
    void enableExtension(String extensionId);
}
