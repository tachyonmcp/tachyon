/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.features.resources;

import dev.tachyonmcp.api.server.ServerFeature;
import dev.tachyonmcp.api.server.domain.UriTemplate;
import dev.tachyonmcp.api.server.features.resources.AsyncResourceFn;
import dev.tachyonmcp.api.server.features.resources.ResourceTemplateDescriptor;

record ResourceTemplateEntry(
        ResourceTemplateDescriptor descriptor, AsyncResourceFn fn, UriTemplate uriTemplate, boolean privateCaching)
        implements ServerFeature<ResourceTemplateDescriptor> {

    /**
     * Creates a resource template entry from its descriptor and handler.
     *
     * @param descriptor the resource template descriptor
     * @param fn         the resource function
     * @return the created resource template entry
     */
    public static ResourceTemplateEntry of(
            ResourceTemplateDescriptor descriptor, AsyncResourceFn fn, boolean privateCaching) {
        return new ResourceTemplateEntry(descriptor, fn, UriTemplate.create(descriptor.uriTemplate()), privateCaching);
    }
}
