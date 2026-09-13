/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.protocol.mcp.v2025_11_25.codecs;

import dev.tachyonmcp.core.protocol.mcp.AbstractMcpRequestMapper;
import tools.jackson.databind.node.ObjectNode;

/** Request mapper for MCP 2025-11-25. */
public final class McpRequestMapper extends AbstractMcpRequestMapper {

    @Override
    protected <T> T convert(ObjectNode node, Class<T> type) {
        final var codec = CodecRegistry.codecFor(type);
        if (codec == null) throw invalidParams("Unsupported params type " + type.getSimpleName());
        return decodeParams(node, codec::decode);
    }
}
