/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.features.tasks;

import dev.tachyonmcp.api.server.domain.ServerError;
import dev.tachyonmcp.api.server.extensions.AdvertiseMode;
import dev.tachyonmcp.api.server.extensions.ServerExtension;
import dev.tachyonmcp.core.server.domain.ServerErrors;
import dev.tachyonmcp.core.server.session.DispatchContext;
import org.jspecify.annotations.Nullable;

/** MCP tasks extension declaration and per-request capability gate. */
public final class TasksExtension implements ServerExtension {

    /** Extension identifier advertised during server initialization and declared by clients. */
    public static final String ID = "io.modelcontextprotocol/tasks";

    private static final TasksExtension INSTANCE = new TasksExtension();

    private TasksExtension() {}

    /** Returns the shared tasks extension used during server setup. */
    public static TasksExtension instance() {
        return INSTANCE;
    }

    public static @Nullable ServerError requireDeclared(DispatchContext context) {
        if (context.protocol().supportsSessions() || context.isExtensionEnabled(ID)) {
            return null;
        }
        return ServerErrors.missingRequiredExtension(ID);
    }

    @Override
    public String extensionId() {
        return ID;
    }

    @Override
    public AdvertiseMode advertiseMode() {
        return AdvertiseMode.ALWAYS;
    }
}
