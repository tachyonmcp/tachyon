/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp;

import dev.tachyonmcp.api.server.features.tasks.Tasks;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.extensions.tasks.TasksExtension;

/** Reaches the tasks façade of a server built with {@link TasksExtension}. */
public final class TasksSupport {

    private TasksSupport() {}

    /**
     * Returns the task registry of {@code server}.
     *
     * @param server a server built with the tasks extension
     * @return the task registry
     */
    public static Tasks tasks(TachyonServer server) {
        return server.extension(TasksExtension.class).orElseThrow().tasks();
    }
}
