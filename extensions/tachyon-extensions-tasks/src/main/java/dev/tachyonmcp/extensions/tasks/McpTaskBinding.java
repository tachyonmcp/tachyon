/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.tasks;

import dev.tachyonmcp.api.server.domain.ProgressToken;
import dev.tachyonmcp.api.server.domain.ServerCapabilities;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.core.protocol.RequestMappingException;
import dev.tachyonmcp.core.server.features.subscriptions.SubscriptionTopic;
import dev.tachyonmcp.core.server.features.tasks.TaskRuntime;
import dev.tachyonmcp.core.server.features.tasks.TasksExtensionSupport;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.server.security.Owner;
import dev.tachyonmcp.core.server.session.DispatchContext;
import dev.tachyonmcp.extensions.tasks.engine.TaskEngine;
import dev.tachyonmcp.extensions.tasks.engine.TaskEvents;
import dev.tachyonmcp.extensions.tasks.engine.TaskRoute;
import java.util.List;
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
    private final SubscriptionTopic<TaskListen> subscriptionTopic;

    /** What one {@code subscriptions/listen} stream follows, and who listens. */
    private record TaskListen(Owner listener, Set<String> taskIds) {}

    McpTaskBinding(ServerEngine server, TaskEngine engine) {
        this.server = Objects.requireNonNull(server, "server");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.subscriptionTopic = taskIdsTopic(engine);
    }

    /**
     * Makes {@code taskIds} a {@code subscriptions/listen} filter key and routes task status to it.
     * Every listed id is authorized when the stream opens, by the connector's {@code get}: it fails
     * closed, and a listener left with no readable id drops the topic. Every status pushed to the
     * stream is checked again against the task's owner at that moment, in memory, so a stream that
     * named a task before it existed never follows it once someone else owns it.
     */
    void registerSubscriptionTopic() {
        server.subscriptions().register(subscriptionTopic);
    }

    private static SubscriptionTopic<TaskListen> taskIdsTopic(TaskEngine engine) {
        var taskIds = SubscriptionTopic.strings("taskIds");
        return new SubscriptionTopic<>(
                taskIds.filterKey(),
                (context, filter) -> {
                    var requested = taskIds.decoder().decode(context, filter);
                    if (requested == null) {
                        return null;
                    }
                    var missingExtension = TasksExtensionSupport.requireDeclared(context);
                    if (missingExtension != null) {
                        throw new RequestMappingException(missingExtension);
                    }
                    return new TaskListen(Owner.of(context.securityContext()), requested);
                },
                (context, requested) -> {
                    var readable = engine.readableTaskIds(context, requested.taskIds());
                    return readable.isEmpty() ? null : new TaskListen(requested.listener(), readable);
                },
                listen -> List.copyOf(listen.taskIds()));
    }

    @Override
    public boolean executionConfigured() {
        return true;
    }

    @Override
    public TaskSnapshot publish(DispatchContext context, TaskSnapshot snapshot, @Nullable ProgressToken progressToken) {
        return engine.publishAs(context, snapshot, McpTaskRoute.of(context.sessionId(), progressToken));
    }

    @Override
    public ServerCapabilities.Tasks capability(boolean hasTaskAugmentedTools) {
        return new ServerCapabilities.Tasks(engine.settings().list(), true, true);
    }

    @Override
    public void onStatus(TaskSnapshot snapshot, TaskRoute route) {
        server.notifyTaskStatus(snapshot, route instanceof McpTaskRoute mcp ? mcp.sessionId() : null);
        var taskId = snapshot.taskId();
        server.subscriptions()
                .publish(
                        subscriptionTopic,
                        listen -> listen.taskIds().contains(taskId) && engine.admits(listen.listener(), taskId),
                        "notifications/tasks",
                        mapper -> mapper.taskStatusNotificationParams(snapshot));
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
