/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.protocol.mcp.v2026_07_28.codecs;

import dev.tachyonmcp.api.json.PayloadDeserializer;
import dev.tachyonmcp.core.protocol.mcp.AbstractMcpRequestMapper;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.SubscriptionsListenRequestParams;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
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
    public SubscriptionListenRequest subscriptionsListen(@Nullable Object params) {
        var listenParams = convert(asObject(params), SubscriptionsListenRequestParams.class);
        var filter = listenParams.notifications();
        if (filter == null) {
            return new SubscriptionListenRequest(false, false, false, Set.of(), Set.of());
        }
        var resourceSubscriptions = filter.resourceSubscriptions() != null
                ? filter.resourceSubscriptions().stream()
                        .filter(Objects::nonNull)
                        .collect(Collectors.toUnmodifiableSet())
                : Set.<String>of();
        return new SubscriptionListenRequest(
                Boolean.TRUE.equals(filter.toolsListChanged()),
                Boolean.TRUE.equals(filter.promptsListChanged()),
                Boolean.TRUE.equals(filter.resourcesListChanged()),
                resourceSubscriptions,
                filter.taskIds() != null
                        ? filter.taskIds().stream().filter(Objects::nonNull).collect(Collectors.toUnmodifiableSet())
                        : Set.of());
    }
}
