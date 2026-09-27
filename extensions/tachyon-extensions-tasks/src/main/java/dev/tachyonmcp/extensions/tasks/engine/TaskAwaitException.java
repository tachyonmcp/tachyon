/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.tasks.engine;

import dev.tachyonmcp.api.annotations.LegacyApi;
import dev.tachyonmcp.api.server.features.tasks.TaskState;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/** A legacy blocking wait on a task ended without the status it waited for. */
@LegacyApi
public final class TaskAwaitException extends Exception {

    /** Why a wait ended. */
    public enum Reason {
        /** The task's {@code ttl} elapsed before it became terminal, so it may be gone. */
        EXPIRED,
        /** The wait's bound passed before the task became terminal. */
        TIMED_OUT,
        /** The task is terminal in another status than the wait needs, for example {@code completed} for a cancel. */
        TERMINAL
    }

    /** Why the wait ended. */
    private final Reason reason;

    /** The terminal status for {@link Reason#TERMINAL}, else {@code null}. */
    private final @Nullable TaskState status;

    private TaskAwaitException(String message, Reason reason, @Nullable TaskState status) {
        super(message, null, false, false);
        this.reason = reason;
        this.status = status;
    }

    static TaskAwaitException expired(String taskId) {
        return new TaskAwaitException("Task " + taskId + " expired before it became terminal", Reason.EXPIRED, null);
    }

    static TaskAwaitException timedOut(Duration maxWait) {
        return new TaskAwaitException("no terminal status within " + maxWait, Reason.TIMED_OUT, null);
    }

    static TaskAwaitException terminal(TaskState status) {
        return new TaskAwaitException("already in terminal status " + status, Reason.TERMINAL, status);
    }

    /**
     * Returns why the wait ended.
     *
     * @return the reason
     */
    public Reason reason() {
        return reason;
    }

    /**
     * Returns the task's terminal status for {@link Reason#TERMINAL}.
     *
     * @return the status, or {@code null} for another reason
     */
    public @Nullable TaskState status() {
        return status;
    }
}
