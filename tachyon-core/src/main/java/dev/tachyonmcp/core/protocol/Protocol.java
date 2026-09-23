/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.protocol;

import dev.tachyonmcp.core.runtime.ChannelContext;
import dev.tachyonmcp.core.runtime.DefaultChannelContext;
import dev.tachyonmcp.core.server.ServerBuilder;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.http.HttpRequest;
import java.util.List;

/**
 * SPI for protocol versions, loadable via {@link java.util.ServiceLoader}.
 *
 * <p>Each implementation represents one negotiated server version (e.g. MCP 2025-11-25, MCP 2026-07-28)
 * and encapsulates the request matching predicate, response mapper, and per-channel context factory.
 * The endpoint path is server configuration, not a protocol trait: see
 * {@link dev.tachyonmcp.core.server.config.NetworkConfig#endpointPath()}.
 *
 * <p>To register an implementation, add its fully-qualified class name to
 * {@code META-INF/services/dev.tachyonmcp.core.protocol.Protocol}.
 */
public interface Protocol {

    /** Protocol family name, e.g. {@code "mcp"}. */
    String familyName();

    /**
     * Server's negotiated version string, e.g. {@code "2025-11-25"}.
     * Used for version comparison: ISO-date format ensures lexicographic order is chronological.
     */
    String versionString();

    default int priority() {
        return 0;
    }

    /**
     * Whether this protocol version supports a server-side session established via
     * {@code initialize}. {@code true} for versions where sessions are part of the protocol (e.g.
     * MCP 2025-11-25) — a deployment can still choose to run those statelessly via
     * {@link ServerBuilder} session config, that's an orthogonal server
     * choice, not a protocol trait. {@code false} for fully stateless, per-request protocol
     * versions (e.g. MCP 2026-07-28), which removed sessions from the protocol entirely — every
     * request self-describes via {@code _meta}, and no {@code initialize} handshake exists.
     */
    default boolean supportsSessions() {
        return true;
    }

    /**
     * Returns {@code true} when this implementation can handle the given HTTP request, which has
     * already been routed to the MCP endpoint. POST requests are matched by
     * {@code MCP-Protocol-Version} header compatibility; other methods (GET for SSE, DELETE for
     * session close, OPTIONS) match any protocol version that serves them.
     */
    boolean matches(HttpRequest request);

    /** Response mapper for this protocol version. */
    ProtocolResponseMapper responseMapper();

    /** Request mapper for this protocol version. */
    ProtocolRequestMapper requestMapper();

    default ChannelContext createInteractionContext() {
        return new DefaultChannelContext(this);
    }

    /**
     * Per-request Netty pipeline handlers this protocol version needs, in the order they must run
     * (e.g. request-shape validation before extension negotiation). Added once per channel alongside
     * every other registered protocol's handlers — a channel's negotiated protocol isn't fixed at
     * construction time (a single keep-alive connection can carry requests for different negotiated
     * versions, e.g. behind a proxy that pools upstream connections across unrelated clients), so
     * each handler must recognize and no-op for a request that didn't negotiate this version.
     * Returned handlers are shared across every channel for this server, so each must be
     * {@code @Sharable}. Empty by default.
     */
    default List<ChannelHandler> requestHandlers(ServerEngine server) {
        return List.of();
    }
}
