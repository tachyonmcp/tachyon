/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.extensions;

/**
 * Controls whether a client must declare a {@link ServerExtension} before Tachyon dispatches the
 * JSON-RPC methods that extension owns (per
 * <a href="https://modelcontextprotocol.io/seps/2133-extensions">SEP-2133</a>).
 *
 * <p>A client declares an extension through the capability mechanism of the active protocol version:
 * {@code initialize.params.capabilities.extensions} for the MCP 2025-11-25 session, or
 * {@code _meta."io.modelcontextprotocol/clientCapabilities".extensions} on each MCP 2026-07-28
 * request. The policy never affects advertisement (see {@link AdvertiseMode}), core MCP methods, or
 * methods no installed extension owns.
 */
public enum ExtensionNegotiation {

    /**
     * Opt-in, for mandatory extensions. The client MUST declare the extension. A call to an
     * extension-owned method without that declaration is rejected with Missing Required Client
     * Capability ({@code -32021} under MCP 2026-07-28, {@code -32003} under MCP 2025-11-25) carrying
     * {@code data.requiredCapabilities.extensions.<extensionId>}; the handler is not invoked.
     *
     * <p>Under MCP 2025-11-25 the declaration is kept on the session, so a stateless server (sessions
     * disabled) retains it only for the {@code initialize} request. Later extension calls are
     * rejected even on the same TCP connection. Enable sessions when 2025-11-25 clients use a {@code REQUIRED} extension.
     */
    REQUIRED,

    /**
     * Default. Calls to the extension's registered methods are dispatched whether or not the client
     * declared it (SEP-2133 makes the client-capability check a SHOULD, not a MUST). Nothing is
     * synthesized: an undeclared extension stays disabled on the request context,
     * {@link ServerExtension#onConnectionInit} does not fire, and no extension-specific settings or
     * features are turned on. Handlers that depend on the client's support check
     * {@code isExtensionEnabled} and fall back to core behavior (SEP-2133 graceful degradation).
     */
    OPTIONAL
}
