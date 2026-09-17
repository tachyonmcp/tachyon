/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.protocol.mcp.v2026_07_28;

import dev.tachyonmcp.core.protocol.Protocol;
import dev.tachyonmcp.core.protocol.ProtocolRequestMapper;
import dev.tachyonmcp.core.protocol.ProtocolResponseMapper;
import dev.tachyonmcp.core.protocol.mcp.McpHeaderNames;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.codecs.McpResponseMapper;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.transport.ExtensionNegotiationHandler;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.transport.RequestValidationHandler;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.transport.RequiredHeadersHandler;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import java.util.List;

/** MCP protocol implementation for the modern, per-request 2026-07-28 revision. */
public final class McpProtocol implements Protocol {

    private static final String ENDPOINT = "/mcp";
    public static final String VERSION = "2026-07-28";
    private static final ProtocolResponseMapper RESPONSE_MAPPER = new McpResponseMapper();
    private static final ProtocolRequestMapper REQUEST_MAPPER =
            new dev.tachyonmcp.core.protocol.mcp.v2026_07_28.codecs.McpRequestMapper();

    @Override
    public String endpoint() {
        return ENDPOINT;
    }

    @Override
    public String familyName() {
        return "mcp";
    }

    @Override
    public String versionString() {
        return VERSION;
    }

    @Override
    public boolean matches(HttpRequest request) {
        return request.method() == HttpMethod.POST
                && request.uri().startsWith(endpoint())
                && VERSION.equals(request.headers().get(McpHeaderNames.MCP_PROTOCOL_VERSION));
    }

    @Override
    public ProtocolResponseMapper responseMapper() {
        return RESPONSE_MAPPER;
    }

    @Override
    public ProtocolRequestMapper requestMapper() {
        return REQUEST_MAPPER;
    }

    /** 2026-07-28 removed protocol-level sessions: every request self-describes via {@code _meta}. */
    @Override
    public boolean supportsSessions() {
        return false;
    }

    @Override
    public List<ChannelHandler> requestHandlers(ServerEngine server) {
        return List.of(
                new RequestValidationHandler(),
                new RequiredHeadersHandler(server),
                new ExtensionNegotiationHandler(server.extensions()));
    }
}
