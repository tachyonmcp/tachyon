/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.domain;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The terminal outcome of a task: either {@link Completed} (a normal {@code tools/call}-shaped
 * outcome, including a tool-level error) or {@link Failed} (a genuine JSON-RPC protocol failure).
 * These are structurally distinct on purpose: a {@link Completed} can never be mistaken for a
 * {@link Failed} and vice versa, so the wire status ({@code "completed"} vs {@code "failed"})
 * follows from the type alone.
 */
@ExperimentalApi
public sealed interface TaskResult extends HasMeta permits TaskResult.Completed, TaskResult.Failed {

    /**
     * Creates a completed task carrying the given tool result, including a tool-level error
     * ({@link ToolResult.Error}) — errors within a task's outcome are still {@code "completed"}
     * on the wire, never {@code "failed"}.
     *
     * @param result the tool result
     * @return a completed task result
     */
    static Completed completed(ToolResult result) {
        return new Completed(result);
    }

    /**
     * Creates a completed task carrying only a structured payload, with no content blocks.
     *
     * @param structuredContent the structured payload
     * @return a completed task result
     */
    static Completed completed(Object structuredContent) {
        return new Completed(ToolResult.Success.of(structuredContent, List.of()));
    }

    /**
     * Creates a completed task carrying a tool-level error message. Still {@code "completed"} on
     * the wire, not {@code "failed"} — use {@link #failed(ServerError)} for a genuine protocol
     * failure.
     *
     * @param message the error message
     * @return a completed task result carrying a tool-level error
     */
    static Completed completedWithError(String message) {
        return new Completed(ToolResult.error(message));
    }

    /**
     * Creates a failed task that preserves {@code error} for direct replay as the RPC error.
     *
     * @param error protocol error to replay
     * @return failed task result carrying the protocol error
     */
    static Failed failed(ServerError error) {
        return new Failed(Objects.requireNonNull(error, "error"));
    }

    /**
     * A normal {@code tools/call}-shaped outcome, including a tool-level error.
     *
     * @param result the tool result
     */
    record Completed(ToolResult result) implements TaskResult {
        /**
         * Creates a completed task result.
         *
         * @param result the result
         */
        public Completed {
            Objects.requireNonNull(result, "result");
            if (result instanceof ToolResult.InputRequired || result instanceof ToolResult.Task) {
                throw new IllegalArgumentException("TaskResult.Completed cannot nest a "
                        + result.getClass().getSimpleName() + " result");
            }
        }

        @Override
        public @Nullable Map<String, Object> meta() {
            return result.meta();
        }
    }

    /**
     * A genuine JSON-RPC protocol failure.
     *
     * @param error the protocol error
     */
    record Failed(ServerError error) implements TaskResult {
        /**
         * Creates a failed task result.
         *
         * @param error the error
         */
        public Failed {
            Objects.requireNonNull(error, "error");
        }

        @Override
        public @Nullable Map<String, Object> meta() {
            return null;
        }
    }
}
