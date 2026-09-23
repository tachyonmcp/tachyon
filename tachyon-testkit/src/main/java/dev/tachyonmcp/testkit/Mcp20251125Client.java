/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.testkit;

import java.net.URI;
import org.jspecify.annotations.Nullable;

/**
 * {@link McpClient} for MCP protocol version 2025-11-25 (session-based, {@code initialize}
 * handshake).
 */
public final class Mcp20251125Client extends McpClient {

    /** The MCP protocol version this client speaks. */
    public static final String PROTOCOL_VERSION = "2025-11-25";

    private volatile @Nullable String sessionId;

    /**
     * Creates a client for the given server port.
     *
     * @param port the port of the running Tachyon server
     */
    public Mcp20251125Client(int port) {
        super(port);
    }

    /**
     * Creates a client against an arbitrary MCP endpoint (local or remote, http or https).
     *
     * @param mcpEndpoint the MCP endpoint URI, e.g. {@code https://staging.example.com/mcp}
     */
    public Mcp20251125Client(URI mcpEndpoint) {
        super(mcpEndpoint);
    }

    @Override
    public void sendInitialized(@Nullable String sessionId) throws Exception {
        super.sendInitialized(sessionId);
        this.sessionId = sessionId;
    }

    /**
     * Returns the session id established by {@link #initialize()} or {@link #sendInitialized}.
     *
     * @return the current session id, or {@code null} when the server issued none
     */
    public @Nullable String sessionId() {
        return sessionId;
    }

    @Override
    protected String protocolVersion() {
        return PROTOCOL_VERSION;
    }
}
