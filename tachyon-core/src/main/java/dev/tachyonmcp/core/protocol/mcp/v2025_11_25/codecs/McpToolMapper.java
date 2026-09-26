/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.protocol.mcp.v2025_11_25.codecs;

import dev.tachyonmcp.api.json.JsonSchema;
import dev.tachyonmcp.api.server.domain.Annotations;
import dev.tachyonmcp.api.server.domain.AudioContent;
import dev.tachyonmcp.api.server.domain.BlobResourceContents;
import dev.tachyonmcp.api.server.domain.ContentBlock;
import dev.tachyonmcp.api.server.domain.EmbeddedResource;
import dev.tachyonmcp.api.server.domain.Icon;
import dev.tachyonmcp.api.server.domain.ImageContent;
import dev.tachyonmcp.api.server.domain.ResourceContents;
import dev.tachyonmcp.api.server.domain.ResourceLink;
import dev.tachyonmcp.api.server.domain.Role;
import dev.tachyonmcp.api.server.domain.TextContent;
import dev.tachyonmcp.api.server.domain.TextResourceContents;
import dev.tachyonmcp.api.server.domain.ToolAnnotations;
import dev.tachyonmcp.api.server.features.tools.ToolDescriptor;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.Tool;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ToolExecution;
import dev.tachyonmcp.core.server.json.JsonUtils;
import java.util.List;
import org.jspecify.annotations.Nullable;

final class McpToolMapper {

    private McpToolMapper() {}

    public static ToolResult toDomainResult(Object result) {
        if (result instanceof ToolResult r) return r;
        var text = TextContent.of(result != null ? result.toString() : "");
        return ToolResult.content(text);
    }

    public static Tool toTool(ToolDescriptor d) {
        var schema = d.inputSchema();
        ToolExecution execution = null;
        if (d.taskSupport() != null) {
            execution = new ToolExecution(d.taskSupport().name().toLowerCase());
        }
        return new Tool(
                d.description(),
                schema != null ? schema.json() : JsonSchema.objectSchema().json(),
                execution,
                d.outputSchema() != null ? d.outputSchema().json() : null,
                toProtocolToolAnnotations(d.annotations()),
                JsonUtils.toObjectTree(d.meta()),
                d.name(),
                d.title(),
                toProtocolIcons(d.icons()));
    }

    public static dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ToolAnnotations toProtocolToolAnnotations(
            @Nullable ToolAnnotations domain) {
        if (domain == null) return null;
        return new dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ToolAnnotations(
                domain.title(),
                domain.readOnlyHint(),
                domain.destructiveHint(),
                domain.idempotentHint(),
                domain.openWorldHint());
    }

    @Nullable
    public static List<dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.Icon> toProtocolIcons(
            List<? extends dev.tachyonmcp.api.server.domain.Icon> domain) {
        return ContentBlockMappers.toProtocolIcons(domain);
    }

    public static List<Icon> toDomainIcons(
            @Nullable List<dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.Icon> protocol) {
        if (protocol == null) return List.of();
        return protocol.stream()
                .map(i -> Icon.of(i.src(), i.mimeType(), i.sizes() == null ? List.of() : i.sizes(), i.theme()))
                .toList();
    }

    public static ContentBlock toDomainContentBlock(
            dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ContentBlock protocol) {
        if (protocol == null) return null;
        return switch (protocol) {
            case dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.TextContent t ->
                TextContent.of(t.text(), JsonUtils.toObjectMap(t._meta()), toDomainAnnotations(t.annotations()));
            case dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ImageContent i ->
                ImageContent.of(
                        i.data(), i.mimeType(), toDomainAnnotations(i.annotations()), JsonUtils.toObjectMap(i._meta()));
            case dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.AudioContent a ->
                AudioContent.of(
                        a.data(), a.mimeType(), toDomainAnnotations(a.annotations()), JsonUtils.toObjectMap(a._meta()));
            case dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ResourceLink r ->
                ResourceLink.builder(r.uri(), r.name())
                        .title(r.title())
                        .icons(toDomainIcons(r.icons()))
                        .description(r.description())
                        .mimeType(r.mimeType())
                        .annotations(toDomainAnnotations(r.annotations()))
                        .size(r.size())
                        .meta(JsonUtils.toObjectMap(r._meta()))
                        .build();
            case dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.EmbeddedResource e ->
                EmbeddedResource.of(
                        toDomainResourceContents(e.resource()),
                        toDomainAnnotations(e.annotations()),
                        JsonUtils.toObjectMap(e._meta()));
        };
    }

    public static dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ContentBlock toProtocolContentBlock(
            ContentBlock domain) {
        return ContentBlockMappers.toProtocolContentBlock(domain);
    }

    public static Annotations toDomainAnnotations(
            dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.Annotations protocol) {
        if (protocol == null) return null;
        var audience = protocol.audience() != null
                ? protocol.audience().stream()
                        .map(r -> r == dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.Role.USER
                                ? Role.USER
                                : Role.ASSISTANT)
                        .toList()
                : List.<Role>of();
        return Annotations.of(audience, protocol.priority(), protocol.lastModified());
    }

    public static dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.Annotations toProtocolAnnotations(
            Annotations domain) {
        return ContentBlockMappers.toProtocolAnnotations(domain);
    }

    public static ResourceContents toDomainResourceContents(
            dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ResourceContents protocol) {
        if (protocol == null) return null;
        return switch (protocol) {
            case dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.TextResourceContents t ->
                TextResourceContents.of(t.uri(), t.text(), t.mimeType(), JsonUtils.toObjectMap(t._meta()));
            case dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.BlobResourceContents b ->
                BlobResourceContents.of(b.uri(), b.blob(), b.mimeType(), JsonUtils.toObjectMap(b._meta()));
        };
    }

    public static dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ResourceContents toProtocolResourceContents(
            ResourceContents domain) {
        return ContentBlockMappers.toProtocolResourceContents(domain);
    }
}
