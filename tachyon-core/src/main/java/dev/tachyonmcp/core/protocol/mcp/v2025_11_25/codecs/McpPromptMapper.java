/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.protocol.mcp.v2025_11_25.codecs;

import dev.tachyonmcp.api.server.domain.PromptArgument;
import dev.tachyonmcp.api.server.domain.PromptMessage;
import dev.tachyonmcp.api.server.domain.Role;
import dev.tachyonmcp.api.server.features.prompts.PromptDescriptor;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.Prompt;
import dev.tachyonmcp.core.server.json.JsonUtils;
import java.util.List;
import org.jspecify.annotations.Nullable;

final class McpPromptMapper {

    private McpPromptMapper() {}

    static Prompt toPrompt(PromptDescriptor d) {
        return new Prompt(
                d.description(),
                toProtocolPromptArguments(d.arguments()),
                JsonUtils.toObjectTree(d.meta()),
                d.name(),
                d.title(),
                ContentBlockMappers.toProtocolIcons(d.icons()));
    }

    @Nullable
    static List<dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.PromptArgument> toProtocolPromptArguments(
            List<PromptArgument> domain) {
        if (domain.isEmpty()) return null;
        return domain.stream()
                .map(a -> new dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.PromptArgument(
                        a.description(), a.required(), a.name(), a.title()))
                .toList();
    }

    static dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.PromptMessage toProtocolMessage(PromptMessage domain) {
        if (domain == null) return null;
        return new dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.PromptMessage(
                toProtocolRole(domain.role()), ContentBlockMappers.toProtocolContentBlock(domain.content()));
    }

    static dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.Role toProtocolRole(Role domain) {
        if (domain == null) return null;
        return switch (domain) {
            case USER -> dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.Role.USER;
            case ASSISTANT -> dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.Role.ASSISTANT;
        };
    }
}
