/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.extensions;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.server.extensions.ServerExtension;
import dev.tachyonmcp.core.server.internal.ServerEngine;

/**
 * A {@link ServerExtension} that needs the engine SPI rather than the public
 * {@link dev.tachyonmcp.api.server.extensions.ExtensionContext}: it installs a runtime or registers
 * raw JSON-RPC handlers. For an instance of this type the server calls {@link #bootstrap(ServerEngine)}
 * instead of {@link ServerExtension#bootstrap(dev.tachyonmcp.api.server.extensions.ExtensionContext)}.
 *
 * <p>Not a stability contract: it exposes {@link ServerEngine}, which lives in an internal package.
 */
@InternalApi
public interface EngineExtension extends ServerExtension {

    /**
     * Bootstraps the extension during server construction, after the built-in handlers are registered.
     *
     * @param engine the engine being constructed
     */
    void bootstrap(ServerEngine engine);
}
