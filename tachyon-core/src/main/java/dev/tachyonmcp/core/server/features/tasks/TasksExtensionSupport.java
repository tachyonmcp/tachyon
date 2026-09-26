/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.features.tasks;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.server.domain.ServerError;
import dev.tachyonmcp.core.server.domain.ServerErrors;
import dev.tachyonmcp.core.server.session.DispatchContext;
import org.jspecify.annotations.Nullable;

/**
 * Core-side tasks extension id and per-request gate, shared by the core handlers and the tasks
 * extension. The extension itself lives in {@code dev.tachyonmcp.extensions.tasks.TasksExtension}.
 */
@InternalApi
public final class TasksExtensionSupport {

    /** Extension identifier advertised during server initialization and declared by clients. */
    public static final String ID = "io.modelcontextprotocol/tasks";

    private TasksExtensionSupport() {}

    /**
     * Returns a missing-extension error when a session-less request did not declare {@link #ID}.
     *
     * @param context the current dispatch
     * @return the error, or {@code null} when the request may proceed
     */
    public static @Nullable ServerError requireDeclared(DispatchContext context) {
        if (context.protocol().supportsSessions() || context.isExtensionEnabled(ID)) {
            return null;
        }
        return ServerErrors.missingRequiredExtension(ID);
    }
}
