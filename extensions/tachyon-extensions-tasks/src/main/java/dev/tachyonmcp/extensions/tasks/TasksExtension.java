/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.tasks;

import dev.tachyonmcp.api.annotations.LegacyApi;
import dev.tachyonmcp.api.server.extensions.AdvertiseMode;
import dev.tachyonmcp.api.server.extensions.ConfigurableExtension;
import dev.tachyonmcp.api.server.extensions.ExtensionBuilder;
import dev.tachyonmcp.api.server.extensions.ProvidedBy;
import dev.tachyonmcp.api.server.features.tasks.TaskConnector;
import dev.tachyonmcp.api.server.features.tasks.Tasks;
import dev.tachyonmcp.core.server.features.Pagination;
import dev.tachyonmcp.core.server.features.tasks.TasksExtensionSupport;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.extensions.tasks.engine.TaskEngine;
import dev.tachyonmcp.extensions.tasks.engine.TaskEngineSettings;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * MCP <a href="https://github.com/modelcontextprotocol/modelcontextprotocol/pull/2663">SEP-2663</a>
 * tasks extension: pollable, long-running tool calls whose execution an external system owns.
 * Registering it advertises {@link #ID}, serves {@code tasks/*}, and enables task-augmented tools.
 * Clients on session-less protocols declare {@link #ID} to use task-augmented calls.
 *
 * <pre>{@code
 * TachyonServer.builder()
 *         .withExtension(TasksExtension.class, tasks -> tasks
 *                 .connector(connector)
 *                 .keepAlive(Duration.ofMinutes(10)))
 *         .build();
 *
 * Tasks tasks = server.extension(TasksExtension.class).orElseThrow().tasks();
 * }</pre>
 */
@ProvidedBy(TasksExtensionProvider.class)
public final class TasksExtension implements ConfigurableExtension<TasksExtension.Builder> {

    /** Extension identifier advertised during server initialization and declared by clients. */
    public static final String ID = TasksExtensionSupport.ID;

    /** Default retention window for a terminal task's cached result. */
    public static final Duration DEFAULT_KEEP_ALIVE = TaskEngineSettings.DEFAULT_KEEP_ALIVE;

    /** Default wait between {@code get} calls that serve a blocking legacy {@code tasks/result}. */
    @LegacyApi
    public static final Duration DEFAULT_RESULT_POLL_INTERVAL = TaskEngineSettings.DEFAULT_RESULT_POLL_INTERVAL;

    /** Default longest wait of a blocking legacy {@code tasks/result}. */
    @LegacyApi
    public static final Duration DEFAULT_RESULT_MAX_WAIT = TaskEngineSettings.DEFAULT_RESULT_MAX_WAIT;

    private final TaskEngineSettings settings;
    private volatile @Nullable TaskEngine engine;

    private TasksExtension(TaskEngineSettings settings) {
        this.settings = settings;
    }

    @Override
    public String extensionId() {
        return ID;
    }

    @Override
    public AdvertiseMode advertiseMode() {
        return AdvertiseMode.ALWAYS;
    }

    /**
     * Returns the task registry. It updates tasks that task-augmented tool calls created; it never
     * creates a task nor changes a task's owning session.
     *
     * @return the task registry
     * @throws IllegalStateException if the server has not been built yet
     */
    public Tasks tasks() {
        var current = engine;
        if (current == null) {
            throw new IllegalStateException("TasksExtension is not bootstrapped: build the server first");
        }
        return current;
    }

    /**
     * Creates this server's engine and installs it as the task runtime, before any extension
     * registers task-capable tools; called by {@link TasksExtensionProvider}.
     */
    void install(ServerEngine server) {
        var created = new TaskEngine(settings, server.config().runtime().clock());
        var binding = new McpTaskBinding(server, created);
        created.addListener(binding);
        server.installTaskRuntime(binding);
        engine = created;
    }

    /** Serves the MCP task methods and starts the engine; called by {@link TasksExtensionProvider}. */
    void attach(ServerEngine server) {
        var created = Objects.requireNonNull(engine, "install runs before attach");
        TaskMethodHandlers.register(server, created);
        if (settings.list()) {
            created.onChange(() -> server.broadcastNotification("notifications/tasks/list_changed", Map.of()));
        }
        created.start();
    }

    @Override
    public void shutdown() {
        var current = engine;
        if (current != null) {
            current.stop();
        }
    }

    /** Configures a {@link TasksExtension}. */
    public static final class Builder implements ExtensionBuilder<TasksExtension> {

        private @Nullable TaskConnector connector;
        private int pageSize = Pagination.DEFAULT_PAGE_SIZE;
        private Duration keepAlive = DEFAULT_KEEP_ALIVE;
        private @Nullable Duration pollInterval;
        private Duration resultPollInterval = TaskEngineSettings.DEFAULT_RESULT_POLL_INTERVAL;
        private Duration resultMaxWait = TaskEngineSettings.DEFAULT_RESULT_MAX_WAIT;

        Builder() {}

        /**
         * Sets the connector to the system that owns task execution. Required. Modern operations
         * are required by the connector; legacy {@code tasks/list} and blocking result remain
         * optional.
         *
         * @param connector the task execution connector
         * @return this builder
         */
        public Builder connector(TaskConnector connector) {
            this.connector = Objects.requireNonNull(connector, "connector");
            return this;
        }

        /**
         * Sets the default page size when a list request omits its limit.
         *
         * @param pageSize a positive page size; defaults to the server-wide page size
         * @return this builder
         */
        public Builder pageSize(int pageSize) {
            this.pageSize = pageSize;
            return this;
        }

        /**
         * Sets the retention window for a terminal task's cached result. Default is 5 minutes; zero
         * or negative keeps results indefinitely.
         *
         * @param keepAlive the retention window
         * @return this builder
         */
        public Builder keepAlive(Duration keepAlive) {
            this.keepAlive = Objects.requireNonNull(keepAlive, "keepAlive");
            return this;
        }

        /**
         * Sets the default {@code pollInterval} suggested in task responses. Default is {@code null}
         * (suggest none); pass a value only if it fits how long tasks on this server actually run:
         * spec-compliant requestors throttle their own polling to match it. As a snapshot default,
         * it also paces a blocking {@code tasks/result} served through {@code get}, ahead of
         * {@link #resultPollInterval(Duration)}.
         *
         * @param pollInterval the suggested interval, or {@code null} to suggest none
         * @return this builder
         */
        public Builder pollInterval(@Nullable Duration pollInterval) {
            this.pollInterval = pollInterval;
            return this;
        }

        /**
         * Sets the wait between {@code get} calls that serve a blocking legacy {@code tasks/result}
         * without a connector {@code awaitResult}, and a legacy {@code tasks/cancel} waiting for
         * {@code cancelled}, when the snapshot suggests no {@code pollInterval}. Default is 1 second.
         * Legacy: MCP 2025-11-25 only; 2026-07-28 has no {@code tasks/result} and a fire-and-forget
         * {@code tasks/cancel}.
         *
         * @param resultPollInterval a positive interval
         * @return this builder
         */
        @LegacyApi
        public Builder resultPollInterval(Duration resultPollInterval) {
            this.resultPollInterval = Objects.requireNonNull(resultPollInterval, "resultPollInterval");
            return this;
        }

        /**
         * Sets the longest a blocking legacy {@code tasks/result} or {@code tasks/cancel} polls
         * {@code get} before answering an internal error; the client may ask again. Default is 5
         * minutes. A task whose
         * {@code ttl} elapses first ends the wait with "Task has expired". Does not bound a connector
         * {@code awaitResult}, which owns its wait. Legacy: MCP 2025-11-25 only, where giving up
         * deliberately relaxes the spec's block-until-terminal rule.
         *
         * @param resultMaxWait a positive duration
         * @return this builder
         */
        @LegacyApi
        public Builder resultMaxWait(Duration resultMaxWait) {
            this.resultMaxWait = Objects.requireNonNull(resultMaxWait, "resultMaxWait");
            return this;
        }

        /**
         * Creates the extension.
         *
         * @return a new extension
         * @throws IllegalStateException if no connector was set
         * @throws IllegalArgumentException if a setting is out of range
         */
        @Override
        public TasksExtension build() {
            if (connector == null) {
                throw new IllegalStateException("TasksExtension requires a TaskConnector");
            }
            return new TasksExtension(new TaskEngineSettings(
                    connector, pageSize, keepAlive, pollInterval, resultPollInterval, resultMaxWait));
        }
    }
}
