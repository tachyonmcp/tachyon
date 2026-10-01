/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.runtime;

/**
 * Extension point that can hook into the connection lifecycle and register custom handlers.
 *
 * @param <T> the interaction context type
 */
public interface Extension<T> {

    /**
     * Unique identifier for this extension.
     *
     * @return the extension id
     */
    String extensionId();

    /**
     * Called after connection initialization is complete.
     *
     * @param context the connection context
     */
    default void onConnectionInit(T context) {}

    /**
     * Called when the connection is being closed.
     *
     * @param context the connection context
     */
    default void onConnectionClose(T context) {}

    /** Called during server shutdown to release resources. */
    default void shutdown() {}
}
