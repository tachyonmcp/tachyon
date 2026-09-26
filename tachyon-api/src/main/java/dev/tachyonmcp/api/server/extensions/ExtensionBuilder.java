/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.extensions;

/**
 * Configures and creates a {@link ServerExtension}. A server builder obtains one for a
 * {@link ConfigurableExtension} type, hands it to the caller's configurer, and calls {@link #build()}
 * once when the server is built, so every server gets its own extension instance.
 *
 * @param <E> the extension this builder creates
 */
public interface ExtensionBuilder<E extends ServerExtension> {

    /**
     * Creates the extension from the current configuration.
     *
     * @return a new extension instance
     * @throws IllegalStateException if the configuration is incomplete
     */
    E build();
}
