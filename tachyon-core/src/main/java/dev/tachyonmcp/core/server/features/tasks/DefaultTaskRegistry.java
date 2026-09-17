/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.features.tasks;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.server.domain.ProgressToken;
import dev.tachyonmcp.api.server.features.PaginatedResult;
import dev.tachyonmcp.api.server.features.tasks.TaskConnector;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.core.server.OutboundSseStreamMessageRouter;
import dev.tachyonmcp.core.server.config.TasksConfig;
import dev.tachyonmcp.core.server.features.ChangeSupport;
import dev.tachyonmcp.core.server.features.Pagination;
import dev.tachyonmcp.core.server.internal.AbstractJanitor;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Revision-aware cache of immutable MCP task projections. */
@InternalApi
public final class DefaultTaskRegistry implements TaskRegistry {

    private static final Logger logger = LoggerFactory.getLogger(DefaultTaskRegistry.class);
    private static final Duration JANITOR_INTERVAL = Duration.ofSeconds(30);

    private final ConcurrentHashMap<String, TaskEntry> entries = new ConcurrentHashMap<>();
    private final ServerEngine server;
    private final @Nullable TaskConnector taskConnector;
    private final Duration keepAlive;
    private final @Nullable Duration pollInterval;
    private final int pageSize;
    private final Clock clock;
    private final ChangeSupport changes = new ChangeSupport();
    private final AbstractJanitor janitor = new AbstractJanitor("task-janitor") {
        @Override
        protected void sweep() {
            runJanitorSweep();
        }
    };

    public DefaultTaskRegistry(ServerEngine server, TasksConfig config) {
        this(server, config, Clock.systemUTC());
    }

    public DefaultTaskRegistry(ServerEngine server, TasksConfig config, Clock clock) {
        this.server = Objects.requireNonNull(server, "server");
        this.taskConnector = config.connector();
        this.keepAlive = config.keepAlive();
        this.pollInterval = config.pollInterval();
        this.pageSize = config.pageSize();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Nullable
    TaskConnector taskConnector() {
        return taskConnector;
    }

    @Override
    public boolean executionConfigured() {
        return taskConnector != null;
    }

    @Override
    public TaskSnapshot publish(TaskSnapshot snapshot) {
        return publish(snapshot, OutboundSseStreamMessageRouter.currentSessionId(), null);
    }

    @Override
    public TaskSnapshot publish(TaskSnapshot snapshot, @Nullable ProgressToken progressToken) {
        return publish(snapshot, OutboundSseStreamMessageRouter.currentSessionId(), progressToken);
    }

    private TaskSnapshot publish(
            TaskSnapshot snapshot, @Nullable String sessionId, @Nullable ProgressToken progressToken) {
        Objects.requireNonNull(snapshot, "snapshot");
        var effective = pollInterval == null || snapshot.pollInterval() != null
                ? snapshot
                : TaskSnapshot.builder()
                        .from(snapshot)
                        .pollInterval(pollInterval)
                        .build();
        var changed = new boolean[1];
        var entry = entries.compute(effective.taskId(), (taskId, current) -> {
            if (current == null) {
                changed[0] = true;
                return new TaskEntry(effective, sessionId, progressToken, keepAlive, clock);
            }
            var before = current.snapshot();
            var published = current.publish(effective);
            changed[0] = published != before;
            return current;
        });
        if (changed[0]) {
            server.notifyTaskStatus(entry.snapshot(), entry.sessionId());
            changes.fireOnChange();
        }
        return entry.snapshot();
    }

    @Override
    public void reportProgress(String taskId, double progress, @Nullable Double total, @Nullable String message) {
        var entry = entries.get(taskId);
        if (entry == null) {
            logger.debug("Dropping task progress for unknown taskId={}", taskId);
            return;
        }
        var progressToken = entry.progressToken();
        if (progressToken == null) {
            logger.debug(
                    "Dropping task progress for taskId={}: no progressToken (task was not created by a"
                            + " task-augmented tool call)",
                    taskId);
            return;
        }
        server.notifyTaskProgress(progressToken, entry.sessionId(), progress, total, message);
    }

    @Override
    public @Nullable TaskSnapshot get(String taskId) {
        var entry = entries.get(taskId);
        return entry != null ? entry.snapshot() : null;
    }

    PaginatedResult<TaskSnapshot> listCached(int limit, @Nullable String cursor) {
        var effectiveLimit = resolvePageLimit(limit);
        var snapshots = entries.values().stream()
                .map(TaskEntry::snapshot)
                .sorted(Comparator.comparing(TaskSnapshot::taskId))
                .toList();
        return Pagination.paginate(snapshots, effectiveLimit, cursor, TaskSnapshot::taskId);
    }

    int resolvePageLimit(int requestedLimit) {
        return requestedLimit > 0 ? requestedLimit : pageSize;
    }

    @Override
    public boolean remove(String taskId) {
        var removed = entries.remove(taskId) != null;
        if (removed) {
            changes.fireOnChange();
        }
        return removed;
    }

    public void onChange(Runnable listener) {
        changes.onChange(Objects.requireNonNull(listener, "listener"));
    }

    public void startTtlJanitor() {
        janitor.start(JANITOR_INTERVAL);
    }

    public void stopTtlJanitor() {
        janitor.close();
    }

    void runJanitorSweep() {
        var changed = entries.entrySet().removeIf(entry -> entry.getValue().isResultExpired());
        if (changed) {
            changes.fireOnChange();
        }
    }
}
