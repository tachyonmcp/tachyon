/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.extensions.spi;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.api.server.extensions.ConfigurableExtension;
import dev.tachyonmcp.api.server.extensions.ExtensionBuilder;

/**
 * Creates the builder for one {@link ConfigurableExtension} type.
 *
 * <p>Discoverable via {@link java.util.ServiceLoader}: implementations register themselves in
 * {@code META-INF/services/dev.tachyonmcp.api.server.extensions.spi.ExtensionProvider}, self-reporting
 * the extension class they serve through {@link #extensionType()}. The server resolves a provider
 * only for a type the caller asked for, so a provider on the classpath enables nothing by itself.
 * When shading, merge these service files with the {@code ServicesResourceTransformer}.
 *
 * @param <E> the extension type
 * @param <B> the builder that configures and creates {@code E}
 */
@ExperimentalApi
public interface ExtensionProvider<E extends ConfigurableExtension<B>, B extends ExtensionBuilder<E>> {

    /**
     * Returns the extension type this provider serves.
     *
     * @return the extension class
     */
    Class<E> extensionType();

    /**
     * Creates a fresh, unconfigured builder.
     *
     * @return a new builder
     */
    B newBuilder();
}
