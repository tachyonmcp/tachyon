/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.protocol.mcp.v2026_07_28.codecs;

import dev.tachyonmcp.api.json.JsonObject;
import dev.tachyonmcp.api.json.PayloadDeserializer;
import dev.tachyonmcp.core.protocol.mcp.AbstractMcpRequestMapper;
import dev.tachyonmcp.core.server.json.JsonUtils;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ObjectNode;

/**
 * Request mapper for MCP 2026-07-28.
 *
 * <p>MCP 2026-07-28 ignores the legacy {@code tools/call.task} parameter. Task creation is
 * server-directed through the tasks extension (SEP-2663).
 */
public final class McpRequestMapper extends AbstractMcpRequestMapper {

    @Override
    public ToolCallRequest callTool(@Nullable Object params, PayloadDeserializer payloadDeserializer) {
        return callTool(params, payloadDeserializer, false);
    }

    /**
     * Prefers this version's codecs, falling back to 2025-11-25's for inherited request shapes,
     * whose models come from that version's package.
     */
    @Override
    protected <T> T convert(ObjectNode node, Class<T> type) {
        final var codec = CodecRegistry.codecFor(type);
        if (codec != null) return decodeParams(node, codec::decode);
        final var fallback = dev.tachyonmcp.core.protocol.mcp.v2025_11_25.codecs.CodecRegistry.codecFor(type);
        if (fallback == null) throw invalidParams("Unsupported params type " + type.getSimpleName());
        return decodeParams(node, fallback::decode);
    }

    @Override
    public boolean supportsLegacyTaskAugmentation() {
        return false;
    }

    @Override
    public boolean supportsSubscriptionsListen() {
        return true;
    }

    @Override
    public JsonObject subscriptionFilter(@Nullable Object params) {
        var notifications = asObject(params).get("notifications");
        if (notifications == null || notifications.isNull()) {
            return JsonObject.empty();
        }
        if (!(notifications instanceof ObjectNode filter)) {
            throw invalidParams("notifications must be an object");
        }
        return JsonObject.of(Objects.requireNonNull(JsonUtils.toObjectMap(filter)));
    }
}
