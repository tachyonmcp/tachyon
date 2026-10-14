/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.apps;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.server.extensions.spi.ExtensionProvider;

/**
 * Serves {@link AppsExtension.Builder} to {@code ServerBuilder.withExtension}. Named by
 * {@link dev.tachyonmcp.api.server.extensions.ProvidedBy} on {@link AppsExtension}; not meant to be
 * called directly.
 */
@InternalApi
public final class AppsExtensionProvider implements ExtensionProvider<AppsExtension, AppsExtension.Builder> {

    /** Creates the provider; invoked by the server when {@link AppsExtension} is requested. */
    public AppsExtensionProvider() {}

    @Override
    public Class<AppsExtension> extensionType() {
        return AppsExtension.class;
    }

    @Override
    public AppsExtension.Builder newBuilder() {
        return new AppsExtension.Builder();
    }
}
