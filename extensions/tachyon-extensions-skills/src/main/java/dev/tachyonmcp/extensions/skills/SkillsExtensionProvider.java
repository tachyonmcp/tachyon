/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.skills;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.server.extensions.spi.ExtensionProvider;

/**
 * Serves {@link SkillsExtension.Builder} to {@code ServerBuilder.withExtension}. Named by
 * {@link dev.tachyonmcp.api.server.extensions.ProvidedBy} on {@link SkillsExtension}; not meant to be
 * called directly.
 */
@InternalApi
public final class SkillsExtensionProvider implements ExtensionProvider<SkillsExtension, SkillsExtension.Builder> {

    /** Creates the provider; invoked by the server when {@link SkillsExtension} is requested. */
    public SkillsExtensionProvider() {}

    @Override
    public Class<SkillsExtension> extensionType() {
        return SkillsExtension.class;
    }

    @Override
    public SkillsExtension.Builder newBuilder() {
        return new SkillsExtension.Builder();
    }
}
