/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.protocol.mcp.v2025_11_25.codecs;

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
import dev.tachyonmcp.core.server.json.JsonUtils;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Maps content-block domain types to MCP 2025-11-25 wire models. */
public final class ContentBlockMappers {

    private ContentBlockMappers() {}

    /**
     * Maps domain annotations to the protocol shape.
     *
     * @param domain the domain annotations, or {@code null}
     * @return the protocol annotations, or {@code null} if {@code domain} is {@code null}
     */
    public static dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.Annotations toProtocolAnnotations(
            Annotations domain) {
        if (domain == null) return null;
        var audience = domain.audience().isEmpty()
                ? null
                : domain.audience().stream()
                        .map(r -> r == Role.USER
                                ? dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.Role.USER
                                : dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.Role.ASSISTANT)
                        .toList();
        return new dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.Annotations(
                audience, domain.priority(), domain.lastModified());
    }

    /**
     * Maps domain icons to the protocol shape.
     *
     * @param domain the domain icons, never {@code null} but possibly empty
     * @return the protocol icons, or {@code null} if {@code domain} is empty
     */
    @Nullable
    public static List<dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.Icon> toProtocolIcons(
            List<? extends Icon> domain) {
        if (domain.isEmpty()) return null;
        return domain.stream()
                .map(i -> new dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.Icon(
                        i.src(), i.mimeType(), i.sizes().isEmpty() ? null : i.sizes(), i.theme()))
                .toList();
    }

    /**
     * Maps a domain content block to the protocol shape.
     *
     * @param domain the domain content block, or {@code null}
     * @return the protocol content block, or {@code null} if {@code domain} is {@code null}
     */
    public static dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ContentBlock toProtocolContentBlock(
            ContentBlock domain) {
        if (domain == null) return null;
        return switch (domain) {
            case TextContent t ->
                new dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.TextContent(
                        t.type().discriminator(),
                        t.text(),
                        toProtocolAnnotations(t.annotations()),
                        JsonUtils.toObjectTree(t.meta()));
            case ImageContent i ->
                new dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ImageContent(
                        i.type().discriminator(),
                        i.data(),
                        i.mimeType(),
                        toProtocolAnnotations(i.annotations()),
                        JsonUtils.toObjectTree(i.meta()));
            case AudioContent a ->
                new dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.AudioContent(
                        a.type().discriminator(),
                        a.data(),
                        a.mimeType(),
                        toProtocolAnnotations(a.annotations()),
                        JsonUtils.toObjectTree(a.meta()));
            case ResourceLink r ->
                new dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ResourceLink(
                        r.type().discriminator(),
                        r.name(),
                        r.title(),
                        toProtocolIcons(r.icons()),
                        r.uri(),
                        r.description(),
                        r.mimeType(),
                        toProtocolAnnotations(r.annotations()),
                        r.size(),
                        JsonUtils.toObjectTree(r.meta()));
            case EmbeddedResource e ->
                new dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.EmbeddedResource(
                        e.type().discriminator(),
                        toProtocolResourceContents(e.resource()),
                        toProtocolAnnotations(e.annotations()),
                        JsonUtils.toObjectTree(e.meta()));
        };
    }

    /**
     * Maps domain resource contents to the protocol shape.
     *
     * @param domain the domain resource contents, or {@code null}
     * @return the protocol resource contents, or {@code null} if {@code domain} is {@code null}
     */
    public static dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ResourceContents toProtocolResourceContents(
            ResourceContents domain) {
        if (domain == null) return null;
        return switch (domain) {
            case TextResourceContents t ->
                new dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.TextResourceContents(
                        t.text(), t.uri(), t.mimeType(), JsonUtils.toObjectTree(t.meta()));
            case BlobResourceContents b ->
                new dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.BlobResourceContents(
                        b.blob(), b.uri(), b.mimeType(), JsonUtils.toObjectTree(b.meta()));
        };
    }
}
