/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.tasks;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.server.extensions.spi.ExtensionProvider;
import dev.tachyonmcp.core.server.extensions.EngineBinding;
import dev.tachyonmcp.core.server.internal.ServerEngine;

/**
 * Serves {@link TasksExtension.Builder} to {@code ServerBuilder.withExtension} and bootstraps the
 * extension on the engine. Named by {@link dev.tachyonmcp.api.server.extensions.ProvidedBy} on
 * {@link TasksExtension}; not meant to be called directly.
 */
@InternalApi
public final class TasksExtensionProvider
        implements ExtensionProvider<TasksExtension, TasksExtension.Builder>, EngineBinding<TasksExtension> {

    /** Creates the provider; invoked by the server when {@link TasksExtension} is requested. */
    public TasksExtensionProvider() {}

    @Override
    public Class<TasksExtension> extensionType() {
        return TasksExtension.class;
    }

    @Override
    public TasksExtension.Builder newBuilder() {
        return new TasksExtension.Builder();
    }

    @Override
    public void bootstrap(TasksExtension extension, ServerEngine engine) {
        extension.attach(engine);
    }
}
