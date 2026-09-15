/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.spring.boot;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.core.server.ServerBuilder;

/**
 * Bean callback that adjusts the {@link ServerBuilder} after {@link TachyonProperties} and
 * extension beans are applied, before the server is built. Annotated beans are registered after
 * construction.
 */ 
@ExperimentalApi
@FunctionalInterface
public interface TachyonServerCustomizer {

    /**
     * Customizes the builder.
     *
     * @param builder the server builder
     */
    void customize(ServerBuilder builder);
}
