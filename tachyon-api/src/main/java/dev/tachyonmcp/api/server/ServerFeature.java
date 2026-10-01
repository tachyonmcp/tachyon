/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server;

import dev.tachyonmcp.api.server.domain.HasMeta;

/**
 * Common interface for server features (tools, resources, prompts, tasks).
 *
 * @param <D> the descriptor type for this feature
 */
public interface ServerFeature<D extends ServerFeature.Descriptor> {

    /**
     * Returns the metadata descriptor for this feature.
     *
     * @return the descriptor
     */
    D descriptor();

    /**
     * Descriptor.
     */
    interface Descriptor {
        /**
         * Unique name of this feature.
         *
         * @return the name
         */
        String name();
    }

    /**
     * Request.
     */
    interface Request extends HasMeta {}
}
