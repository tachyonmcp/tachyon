/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.protocol.mcp;

import io.netty.util.AsciiString;
import org.jspecify.annotations.Nullable;

/**
 * Constants for MCP HTTP header names, and the schema keyword that declares the custom ones.
 */
public final class McpHeaderNames {

    /**
     * Header carrying the session identifier.
     */
    public static final String MCP_SESSION_ID = "MCP-Session-Id";
    /**
     * Header carrying the protocol version.
     */
    public static final String MCP_PROTOCOL_VERSION = "MCP-Protocol-Version";
    /**
     * SSE {@code Last-Event-ID} header for reconnection.
     */
    public static final String LAST_EVENT_ID = "Last-Event-ID";

    public static final String MCP_METHOD = "Mcp-Method";
    public static final String MCP_NAME = "Mcp-Name";

    /**
     * Tool input-schema keyword naming the {@link #MCP_PARAM_PREFIX} header a property mirrors into
     * (SEP-2243).
     */
    public static final String X_MCP_HEADER = "x-mcp-header";

    /** Prefix of the headers mirroring {@link #X_MCP_HEADER}-annotated tool arguments (SEP-2243). */
    public static final String MCP_PARAM_PREFIX = "Mcp-Param-";

    /**
     * The {@code params} field {@link #MCP_NAME} mirrors for {@code method}, or {@code null} for a
     * method that addresses no named target and therefore never carries the header (SEP-2243).
     *
     * @param method the JSON-RPC method name from the request body
     * @return {@code "uri"} for {@code resources/read}, {@code "name"} for the other addressed
     *     methods, {@code null} otherwise
     */
    public static @Nullable String mirroredNameField(String method) {
        return switch (method) {
            case "resources/read" -> "uri";
            case "tools/call", "prompts/get" -> "name";
            default -> null;
        };
    }

    /**
     * Whether {@code name} is an {@code Mcp-Param-*} header, matched case-insensitively per RFC 9110.
     *
     * @param name the HTTP header name
     * @return {@code true} if {@code name} starts with {@link #MCP_PARAM_PREFIX}
     */
    public static boolean isParamHeader(CharSequence name) {
        return AsciiString.regionMatches(name, true, 0, MCP_PARAM_PREFIX, 0, MCP_PARAM_PREFIX.length());
    }

    private McpHeaderNames() {}
}
