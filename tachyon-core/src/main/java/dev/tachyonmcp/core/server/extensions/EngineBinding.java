/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.extensions;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.server.extensions.ServerExtension;
import dev.tachyonmcp.core.server.internal.ServerEngine;

/**
 * Engine-level bootstrap for a configurable extension, implemented by its
 * {@link dev.tachyonmcp.api.server.extensions.spi.ExtensionProvider}. It keeps the engine SPI off
 * the extension's public type: for an extension created through {@code withExtension}, the server
 * calls {@link #bootstrap(ServerExtension, ServerEngine)} instead of
 * {@link ServerExtension#bootstrap(dev.tachyonmcp.api.server.extensions.ExtensionContext)}.
 *
 * <p>Not a stability contract: it exposes {@link ServerEngine}, which lives in an internal package.
 *
 * @param <E> the extension type
 */
@InternalApi
public interface EngineBinding<E extends ServerExtension> {

    /**
     * Bootstraps {@code extension} during server construction, after the built-in handlers are
     * registered.
     *
     * @param extension the extension instance this server built
     * @param engine the engine being constructed
     */
    void bootstrap(E extension, ServerEngine engine);
}
