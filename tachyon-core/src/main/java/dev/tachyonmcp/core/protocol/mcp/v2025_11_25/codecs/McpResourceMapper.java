/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.protocol.mcp.v2025_11_25.codecs;

import dev.tachyonmcp.api.server.domain.ResourceContents;
import dev.tachyonmcp.api.server.features.resources.ResourceDescriptor;
import dev.tachyonmcp.api.server.features.resources.ResourceTemplateDescriptor;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.Resource;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ResourceTemplate;
import dev.tachyonmcp.core.server.json.JsonUtils;

final class McpResourceMapper {

    private McpResourceMapper() {}

    static Resource toResource(ResourceDescriptor d) {
        return new Resource(
                d.uri(),
                d.description(),
                d.mimeType(),
                ContentBlockMappers.toProtocolAnnotations(d.annotations()),
                d.size(),
                JsonUtils.toObjectTree(d.meta()),
                d.name(),
                d.title(),
                ContentBlockMappers.toProtocolIcons(d.icons()));
    }

    static ResourceTemplate toResourceTemplate(ResourceTemplateDescriptor descriptor) {
        return new ResourceTemplate(
                descriptor.uriTemplate(),
                descriptor.description(),
                descriptor.mimeType(),
                ContentBlockMappers.toProtocolAnnotations(descriptor.annotations()),
                JsonUtils.toObjectTree(descriptor.meta()),
                descriptor.name(),
                descriptor.title(),
                ContentBlockMappers.toProtocolIcons(descriptor.icons()));
    }

    static dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ResourceContents toProtocolResourceContents(
            ResourceContents domain) {
        return ContentBlockMappers.toProtocolResourceContents(domain);
    }
}
