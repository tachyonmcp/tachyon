/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.tasks;

import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.domain.ProgressToken;
import dev.tachyonmcp.api.server.domain.ServerCapabilities;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.core.server.features.tasks.TaskRuntime;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.extensions.tasks.engine.TaskEngine;
import dev.tachyonmcp.extensions.tasks.engine.TaskEvents;
import dev.tachyonmcp.extensions.tasks.engine.TaskRoute;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCP binding of the {@link TaskEngine}: the {@link TaskRuntime} core's tool and subscription
 * handlers call, and the encoder of engine push traffic into MCP notifications.
 */
final class McpTaskBinding implements TaskRuntime, TaskEvents {

    private static final Logger logger = LoggerFactory.getLogger(McpTaskBinding.class);

    private final ServerEngine server;
    private final TaskEngine engine;

    McpTaskBinding(ServerEngine server, TaskEngine engine) {
        this.server = Objects.requireNonNull(server, "server");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    @Override
    public boolean executionConfigured() {
        return true;
    }

    @Override
    public TaskSnapshot publish(
            TaskSnapshot snapshot, @Nullable String sessionId, @Nullable ProgressToken progressToken) {
        return engine.publish(snapshot, McpTaskRoute.of(sessionId, progressToken));
    }

    @Override
    public Set<String> readableTaskIds(InteractionContext ctx, Set<String> taskIds) {
        return engine.readableTaskIds(ctx, taskIds);
    }

    @Override
    public ServerCapabilities.Tasks capability(boolean hasTaskAugmentedTools) {
        return new ServerCapabilities.Tasks(engine.settings().list(), true, true);
    }

    @Override
    public void onStatus(TaskSnapshot snapshot, TaskRoute route) {
        server.notifyTaskStatus(snapshot, route instanceof McpTaskRoute mcp ? mcp.sessionId() : null);
    }

    @Override
    public void onProgress(
            String taskId, TaskRoute route, double progress, @Nullable Double total, @Nullable String message) {
        if (!(route instanceof McpTaskRoute mcp) || mcp.progressToken() == null) {
            logger.debug(
                    "Dropping task progress for taskId={}: no progressToken (task was not created by a"
                            + " task-augmented tool call)",
                    taskId);
            return;
        }
        server.notifyTaskProgress(mcp.progressToken(), mcp.sessionId(), progress, total, message);
    }
}
