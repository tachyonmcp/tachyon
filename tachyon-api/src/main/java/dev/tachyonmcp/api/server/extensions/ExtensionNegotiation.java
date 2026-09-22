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
     * Default. The client MUST declare the extension. A call to an extension-owned method without that
     * declaration is rejected with Missing Required Client Capability ({@code -32021} under MCP
     * 2026-07-28) carrying {@code data.requiredCapabilities.extensions.<extensionId>}; the handler is
     * not invoked.
     */
    REQUIRED,

    /**
     * Compatibility mode for clients that know an extension's wire methods but skip negotiation. The
     * extension is still advertised, and calls to its registered methods are dispatched even when the
     * client did not declare it. Nothing is synthesized: the extension stays disabled on the request context,
     * {@link ServerExtension#onConnectionInit} does not fire, and no extension-specific optional
     * settings or features are turned on.
     */
    OPTIONAL
}
