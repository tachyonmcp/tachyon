/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.runtime;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.security.SecurityContext;
import dev.tachyonmcp.core.protocol.Protocol;
import org.jspecify.annotations.Nullable;

/**
 * Context for an MCP channel, providing access to the protocol, session, and lifecycle.
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
     * Sets the security context of the request now on this channel. The HTTP pipelining gate admits
     * one request per channel at a time, and every request is authenticated, so this never outlives
     * the request it was set for. Dispatch contexts copy it when created.
     *
     * @param securityContext the current request's security context
     */
    void setSecurityContext(SecurityContext securityContext);

    /**
     * Enables an extension for this channel.
     *
     * @param extensionId the extension identifier
     */
    void enableExtension(String extensionId);
}
