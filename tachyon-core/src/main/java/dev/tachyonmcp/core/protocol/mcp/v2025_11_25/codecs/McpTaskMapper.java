/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.protocol.mcp.v2025_11_25.codecs;

import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.api.server.features.tasks.TaskState;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.CancelTaskResult;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.CreateTaskResult;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.GetTaskResult;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.Task;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.TaskStatus;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.TaskStatusNotificationParams;
import dev.tachyonmcp.core.server.json.JsonUtils;

final class McpTaskMapper {

    private McpTaskMapper() {}

    private static TaskStatus toWireStatus(TaskState status) {
        return switch (status) {
            case SUBMITTED, WORKING -> TaskStatus.WORKING;
            case REJECTED, AUTH_REQUIRED, FAILED -> TaskStatus.FAILED;
            case INPUT_REQUIRED -> TaskStatus.INPUT_REQUIRED;
            case COMPLETED -> TaskStatus.COMPLETED;
            case CANCELLED -> TaskStatus.CANCELLED;
            case UNKNOWN -> throw new UnsupportedOperationException("Unsupported status: " + status);
        };
    }

    static Task toTaskProto(TaskSnapshot entry) {
        return new Task(
                entry.taskId(),
                toWireStatus(entry.status()),
                entry.statusMessage(),
                entry.createdAt(),
                entry.lastUpdatedAt(),
                entry.ttl(),
                entry.pollInterval());
    }

    static GetTaskResult toGetTaskResult(TaskSnapshot entry) {
        return new GetTaskResult(
                JsonUtils.toObjectTree(entry.meta()),
                entry.taskId(),
                toWireStatus(entry.status()),
                entry.statusMessage(),
                entry.createdAt(),
                entry.lastUpdatedAt(),
                entry.ttl(),
                entry.pollInterval());
    }

    public static CancelTaskResult toCancelTaskResult(TaskSnapshot entry) {
        return new CancelTaskResult(
                JsonUtils.toObjectTree(entry.meta()),
                entry.taskId(),
                toWireStatus(entry.status()),
                entry.statusMessage(),
                entry.createdAt(),
                entry.lastUpdatedAt(),
                entry.ttl(),
                entry.pollInterval());
    }

    static CreateTaskResult toCreateTaskResult(TaskSnapshot entry) {
        return new CreateTaskResult(toTaskProto(entry), JsonUtils.toObjectTree(entry.meta()), null);
    }

    static TaskStatusNotificationParams toStatusNotification(TaskSnapshot entry) {
        return new TaskStatusNotificationParams(
                JsonUtils.toObjectTree(entry.meta()),
                entry.taskId(),
                toWireStatus(entry.status()),
                entry.statusMessage(),
                entry.createdAt(),
                entry.lastUpdatedAt(),
                entry.ttl(),
                entry.pollInterval());
    }
}
