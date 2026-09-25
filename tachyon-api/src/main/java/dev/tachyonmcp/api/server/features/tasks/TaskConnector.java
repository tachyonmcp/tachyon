/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.features.tasks;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.api.annotations.LegacyApi;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Connects MCP task operations to the system that owns task execution.
 *
 * <p>The modern Tasks extension defines {@code tasks/get}, {@code tasks/cancel}, and {@code
 * tasks/update} as one contract, so all three operations are required. Legacy list and blocking
 * result operations remain optional.
 *
 * <p>The connector is the only access check: Tachyon forwards every {@code tasks/*} request to it
 * without deciding who may reach a task, and calls {@code get} for each task id a
 * {@code subscriptions/listen} stream names, keeping only the ids it answers. Authorize each call from its {@link
 * dev.tachyonmcp.api.runtime.InteractionContext} (for example against the session that started the
 * work) and answer a task the caller may not see with {@link TaskNotFoundException}, as for an
 * unknown id. Without such checks a task id is a bearer capability: generate ids that cannot be
 * guessed, as the MCP tasks specification requires.
 *
 * <p>Operations are synchronous by design. Tachyon invokes them from request-serving virtual
 * threads, so an implementation may block while calling an external workflow engine or job store.
 * Tachyon never closes the connector or dependencies captured by its functions; lifecycle remains
 * owned by the application.
 */
@ExperimentalApi
public final class TaskConnector {

    private final TaskGetFn get;
    private final TaskCancelFn cancel;
    private final TaskUpdateFn update;

    @LegacyApi
    private final @Nullable TaskListFn list;

    @LegacyApi
    private final @Nullable TaskAwaitResultFn awaitResult;

    private TaskConnector(
            TaskGetFn get,
            TaskCancelFn cancel,
            TaskUpdateFn update,
            @Nullable TaskListFn list,
            @Nullable TaskAwaitResultFn awaitResult) {
        this.get = get;
        this.cancel = cancel;
        this.update = update;
        this.list = list;
        this.awaitResult = awaitResult;
    }

    /** Creates a new connector builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns the authoritative task lookup used by {@code tasks/get}. Always present. */
    public TaskGetFn get() {
        return get;
    }

    /** Returns the required {@code tasks/cancel} operation. */
    public TaskCancelFn cancel() {
        return cancel;
    }

    /** Returns the required {@code tasks/update} operation. */
    public TaskUpdateFn update() {
        return update;
    }

    /**
     * Returns the legacy {@code tasks/list} hook, or {@code null} when unsupported. The hook scopes
     * results to the caller; see {@link TaskListFn#apply}.
     * <p>
     * Legacy: legacy surface kept for MCP 2025-11-25 (pre-SEP-2663) compatibility; no
     *     equivalent exists in the modern tasks extension. Not scheduled for removal.
     * </p>
     */
    @LegacyApi
    public @Nullable TaskListFn list() {
        return list;
    }

    /**
     * Returns the legacy blocking {@code tasks/result} hook, or {@code null} when unsupported.
     * <p>
     * Legacy: legacy surface kept for MCP 2025-11-25 (pre-SEP-2663) compatibility; no
     *     equivalent exists in the modern tasks extension. Not scheduled for removal.
     * </p>
     */
    @LegacyApi
    public @Nullable TaskAwaitResultFn awaitResult() {
        return awaitResult;
    }

    /** Builder for {@link TaskConnector}. */
    public static final class Builder {

        private @Nullable TaskGetFn get;
        private @Nullable TaskCancelFn cancel;
        private @Nullable TaskUpdateFn update;

        private @Nullable TaskListFn list;

        private @Nullable TaskAwaitResultFn awaitResult;

        private Builder() {}

        /** Sets the authoritative task lookup used by {@code tasks/get}. Required. */
        public Builder get(TaskGetFn get) {
            this.get = Objects.requireNonNull(get, "get");
            return this;
        }

        /** Sets the required cooperative {@code tasks/cancel} operation. */
        public Builder cancel(TaskCancelFn cancel) {
            this.cancel = Objects.requireNonNull(cancel, "cancel");
            return this;
        }

        /** Sets the required {@code tasks/update} operation. */
        public Builder update(TaskUpdateFn update) {
            this.update = Objects.requireNonNull(update, "update");
            return this;
        }

        /**
         * Enables the legacy {@code tasks/list} operation.
         * <p>
         * Legacy: legacy surface kept for MCP 2025-11-25 (pre-SEP-2663) compatibility; no
         *     equivalent exists in the modern tasks extension. Not scheduled for removal.
         * </p>
         */
        @LegacyApi
        public Builder list(TaskListFn list) {
            this.list = Objects.requireNonNull(list, "list");
            return this;
        }

        /**
         * Enables the legacy blocking {@code tasks/result} operation.
         * <p>
         * Legacy: legacy surface kept for MCP 2025-11-25 (pre-SEP-2663) compatibility; no
         *     equivalent exists in the modern tasks extension. Not scheduled for removal.
         * </p>
         */
        @LegacyApi
        public Builder awaitResult(TaskAwaitResultFn awaitResult) {
            this.awaitResult = Objects.requireNonNull(awaitResult, "awaitResult");
            return this;
        }

        /**
         * Builds the connector.
         *
         * @throws IllegalStateException if a modern Tasks operation was not configured
         */
        public TaskConnector build() {
            if (get == null) throw new IllegalStateException("TaskConnector requires get(...)");
            if (cancel == null) throw new IllegalStateException("TaskConnector requires cancel(...)");
            if (update == null) throw new IllegalStateException("TaskConnector requires update(...)");
            return new TaskConnector(get, cancel, update, list, awaitResult);
        }
    }
}
