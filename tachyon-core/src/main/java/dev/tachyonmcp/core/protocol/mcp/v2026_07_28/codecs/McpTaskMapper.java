/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.protocol.mcp.v2026_07_28.codecs;

import dev.tachyonmcp.api.server.domain.TaskResult;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.api.server.features.tasks.TaskState;
import dev.tachyonmcp.core.server.json.JsonUtils;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Task wire mapping for MCP 2026-07-28's tasks extension (SEP-2663). Field names and shapes
 * follow the extension spec rather than 2025-11-25's experimental tasks feature: {@code ttlMs}/
 * {@code pollIntervalMs} (not {@code ttl}/{@code pollInterval}), a {@code resultType}
 * discriminator ({@code "task"} on task creation, {@code "complete"} on {@code tasks/get}), a
 * flat {@code CreateTaskResult} shape (@code CreateTaskResult = Result & Task} — no nesting under
 * a {@code "task"} key), and {@code tasks/cancel} returning an empty acknowledgement rather than
 * the full task state.
 *
 * <p>The wire status enum has exactly five values: {@code working}, {@code input_required},
 * {@code completed}, {@code failed}, {@code cancelled} (per the current tasks extension spec —
 * {@code submitted} and {@code unknown} are not wire values). {@link TaskState#SUBMITTED} folds to
 * {@code "working"}. A2A-only states without an MCP representation are rejected instead of being
 * serialized as an invalid status-specific payload.
 */
final class McpTaskMapper {

    private static final String RESULT_TYPE_TASK = "task";
    private static final String RESULT_TYPE_COMPLETE = "complete";

    private McpTaskMapper() {}

    static String toWireStatus(TaskState status) {
        return switch (status) {
            case SUBMITTED, WORKING -> "working";
            case INPUT_REQUIRED -> "input_required";
            case COMPLETED -> "completed";
            case CANCELLED -> "cancelled";
            case FAILED -> "failed";
            case REJECTED, AUTH_REQUIRED, UNKNOWN ->
                throw new UnsupportedOperationException("Task state cannot be projected to MCP: " + status);
        };
    }

    static JsonNode toCreateTaskResult(TaskSnapshot snapshot) {
        var fields = taskFields(snapshot, effectiveWireStatus(snapshot));
        fields.put("resultType", RESULT_TYPE_TASK);
        return JsonUtils.toObjectNode(fields);
    }

    static JsonNode toGetTaskResult(
            TaskSnapshot snapshot,
            @Nullable JsonNode inlineResult,
            @Nullable JsonNode inlineError,
            @Nullable JsonNode inputRequests) {
        var fields = taskFields(snapshot, effectiveWireStatus(snapshot));
        putIfPresent(fields, "result", inlineResult);
        putIfPresent(fields, "error", inlineError);
        putIfPresent(fields, "inputRequests", inputRequests);
        fields.put("resultType", RESULT_TYPE_COMPLETE);
        return JsonUtils.toObjectNode(fields);
    }

    /** {@code tasks/cancel}'s response is an empty acknowledgement, not the task's state. */
    static JsonNode toCancelTaskResult() {
        return JsonUtils.toObjectNode(Map.of("resultType", RESULT_TYPE_COMPLETE));
    }

    static JsonNode toStatusNotification(
            TaskSnapshot snapshot,
            @Nullable JsonNode inlineResult,
            @Nullable JsonNode inlineError,
            @Nullable JsonNode inputRequests) {
        var fields = taskFields(snapshot, effectiveWireStatus(snapshot));
        putIfPresent(fields, "result", inlineResult);
        putIfPresent(fields, "error", inlineError);
        putIfPresent(fields, "inputRequests", inputRequests);
        return JsonUtils.toObjectNode(fields);
    }

    /**
     * A tool result that completed with {@code isError: true} is a normal {@code completed}
     * outcome, not a task failure: "This status MUST NOT be used for non-JSON-RPC errors ...
     * errors within the context of a protocol method result MUST use the completed status." Only
     * a {@link TaskResult.Failed}, which always carries a genuine protocol {@link
     * dev.tachyonmcp.api.server.domain.ServerError}, maps to {@code "failed"}.
     */
    private static String effectiveWireStatus(TaskSnapshot snapshot) {
        return switch (snapshot.result()) {
            case TaskResult.Completed c -> "completed";
            case TaskResult.Failed f -> "failed";
            case null -> toWireStatus(snapshot.status());
        };
    }

    private static Map<String, Object> taskFields(TaskSnapshot snapshot, String wireStatus) {
        var fields = new LinkedHashMap<String, Object>();
        putMeta(fields, snapshot);
        fields.put("taskId", snapshot.taskId());
        fields.put("status", wireStatus);
        putIfPresent(fields, "statusMessage", snapshot.statusMessage());
        fields.put("createdAt", snapshot.createdAt());
        fields.put("lastUpdatedAt", snapshot.lastUpdatedAt());
        // SEP-2663 types Task.ttlMs as `number | null` (required, nullable) but
        // Task.pollIntervalMs as `number?` (optional) -- ttlMs is always written, even when null;
        // pollIntervalMs is omitted rather than written as null.
        fields.put("ttlMs", durationMillis(snapshot.ttl()));
        putIfPresent(fields, "pollIntervalMs", durationMillis(snapshot.pollInterval()));
        return fields;
    }

    private static void putMeta(Map<String, Object> target, TaskSnapshot snapshot) {
        var meta = snapshot.meta();
        if (meta != null && !meta.isEmpty()) {
            target.put("_meta", meta);
        }
    }

    private static void putIfPresent(Map<String, Object> target, String key, @Nullable Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static @Nullable Long durationMillis(@Nullable Duration duration) {
        return duration != null ? duration.toMillis() : null;
    }
}
