/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.protocol.mcp.v2025_11_25;

import dev.tachyonmcp.core.protocol.Protocol;
import dev.tachyonmcp.core.protocol.ProtocolRequestMapper;
import dev.tachyonmcp.core.protocol.ProtocolResponseMapper;
import dev.tachyonmcp.core.protocol.mcp.McpHeaderNames;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.codecs.McpResponseMapper;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import java.util.Set;

/**
 * {@link Protocol} implementation for the MCP protocol version 2025-11-25.
 * Registered via {@code META-INF/services/dev.tachyonmcp.core.protocol.Protocol}.
 *
 * <p>Accepts client requests for versions 2024-11-05 through 2025-11-25 and negotiates
 * up to this implementation's version (2025-11-25).
 */
public final class McpProtocol implements Protocol {

    private static final String ENDPOINT = "/mcp";
    private static final String PROTOCOL_NAME = "mcp";
    public static final String VERSION = "2025-11-25";

    /** All client-advertised versions this server can handle; negotiates up to VERSION. */
    private static final Set<String> ACCEPTED_CLIENT_VERSIONS = Set.of(
            VERSION,
            "2025-06-18", // best-effort support
            "2025-03-26" // best-effort support
            );

    static final ProtocolResponseMapper RESPONSE_MAPPER = new McpResponseMapper();
    private static final ProtocolRequestMapper REQUEST_MAPPER =
            new dev.tachyonmcp.core.protocol.mcp.v2025_11_25.codecs.McpRequestMapper();

    @Override
    public String endpoint() {
        return ENDPOINT;
    }

    @Override
    public String familyName() {
        return PROTOCOL_NAME;
    }

    @Override
    public String versionString() {
        return VERSION;
    }

    @Override
    public boolean matches(HttpRequest request) {
        if (!request.uri().startsWith(endpoint())) {
            return false;
        }
        if (request.method() == HttpMethod.POST) {
            var clientVersion = request.headers().get(McpHeaderNames.MCP_PROTOCOL_VERSION);
            return clientVersion == null || ACCEPTED_CLIENT_VERSIONS.contains(clientVersion);
        }
        // GET (SSE stream), DELETE (session close), OPTIONS — no version header required
        return true;
    }

    @Override
    public ProtocolResponseMapper responseMapper() {
        return RESPONSE_MAPPER;
    }

    @Override
    public ProtocolRequestMapper requestMapper() {
        return REQUEST_MAPPER;
    }
}
