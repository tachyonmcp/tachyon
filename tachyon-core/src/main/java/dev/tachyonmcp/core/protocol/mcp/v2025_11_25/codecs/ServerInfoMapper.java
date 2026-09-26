/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.protocol.mcp.v2025_11_25.codecs;

import dev.tachyonmcp.api.server.config.ServerIdentity;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.Icon;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.Implementation;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ServerCapabilities;
import dev.tachyonmcp.core.server.json.JsonUtils;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

class ServerInfoMapper {

    private ServerInfoMapper() {}

    private static final ObjectNode EMPTY_NODE = JsonNodeFactory.instance.objectNode();

    public static Implementation toImplementation(ServerIdentity serverIdentity) {
        Implementation.Builder builder = Implementation.builder()
                .name(serverIdentity.name())
                .version(serverIdentity.version())
                .description(serverIdentity.description())
                .title(serverIdentity.title())
                .websiteUrl(serverIdentity.websiteUrl());
        if (!serverIdentity.icons().isEmpty()) {
            builder.icons(serverIdentity.icons().stream()
                    .map(ServerInfoMapper::toIcon)
                    .toList());
        }
        return builder.build();
    }

    private static Icon toIcon(dev.tachyonmcp.api.server.domain.Icon icon) {
        return Icon.builder()
                .src(icon.src())
                .mimeType(icon.mimeType())
                .sizes(icon.sizes().isEmpty() ? null : icon.sizes())
                .theme(icon.theme())
                .build();
    }

    public static ServerCapabilities.Builder toServerCapabilities(
            dev.tachyonmcp.api.server.domain.ServerCapabilities src) {
        ServerCapabilities.Builder builder = ServerCapabilities.builder();
        if (src.completions()) {
            builder.completions(EMPTY_NODE);
        }
        if (src.logging()) {
            builder.logging(EMPTY_NODE);
        }
        if (src.experimental() != null) {
            builder.experimental(JsonUtils.toObjectTree(src.experimental().asMap()));
        }

        if (src.prompts() != null) {
            ServerCapabilities.Prompts.Builder promptsBuilder = ServerCapabilities.Prompts.builder();
            if (src.prompts().listChanged()) {
                promptsBuilder.listChanged(true);
            }

            builder.prompts(promptsBuilder.build());
        }

        if (src.resources() != null) {
            ServerCapabilities.Resources.Builder resourcesBuilder = ServerCapabilities.Resources.builder();
            if (src.resources().listChanged()) {
                resourcesBuilder.listChanged(true);
            }
            if (src.resources().subscribe()) {
                resourcesBuilder.subscribe(true);
            }
            builder.resources(resourcesBuilder.build());
        }

        if (src.tools() != null) {
            ServerCapabilities.Tools.Builder toolsBuilder = ServerCapabilities.Tools.builder();
            if (src.tools().listChanged()) {
                toolsBuilder.listChanged(true);
            }

            builder.tools(toolsBuilder.build());
        }

        if (src.tasks() != null) {
            final var tasksBuilder = ServerCapabilities.Tasks.builder();
            if (src.tasks().list()) {
                tasksBuilder.list(EMPTY_NODE);
            }
            if (src.tasks().cancel()) {
                tasksBuilder.cancel(EMPTY_NODE);
            }
            if (src.tasks().toolCallRequests()) {
                tasksBuilder.requests(
                        new ServerCapabilities.Tasks.Requests(new ServerCapabilities.Tasks.Requests.Tools(EMPTY_NODE)));
            }
            builder.tasks(tasksBuilder.build());
        }
        return builder;
    }
}
