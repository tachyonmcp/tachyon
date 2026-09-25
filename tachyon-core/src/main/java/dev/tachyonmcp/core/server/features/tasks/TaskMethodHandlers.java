/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.features.tasks;

import dev.tachyonmcp.api.server.domain.ServerError;
import dev.tachyonmcp.api.server.features.tasks.TaskAwaitResultRequest;
import dev.tachyonmcp.api.server.features.tasks.TaskCancelRequest;
import dev.tachyonmcp.api.server.features.tasks.TaskGetRequest;
import dev.tachyonmcp.api.server.features.tasks.TaskListRequest;
import dev.tachyonmcp.api.server.features.tasks.TaskNotFoundException;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.api.server.features.tasks.TaskUpdateRequest;
import dev.tachyonmcp.core.protocol.ProtocolRequestMapper;
import dev.tachyonmcp.core.protocol.RequestMappingException;
import dev.tachyonmcp.core.server.RpcMethodHandler;
import dev.tachyonmcp.core.server.domain.ServerErrors;
import dev.tachyonmcp.core.server.session.DispatchContext;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** JSON-RPC adapters for task operations. */
public final class TaskMethodHandlers {

    private TaskMethodHandlers() {}

    public static void register(Map<String, RpcMethodHandler<?, ?>> handlers, DefaultTaskRegistry registry) {
        handlers.put("tasks/list", new TasksListHandler(registry));
        handlers.put("tasks/get", new TasksGetHandler(registry));
        handlers.put("tasks/cancel", new TasksCancelHandler(registry));
        handlers.put("tasks/result", new TasksResultHandler(registry));
        handlers.put("tasks/update", new TasksUpdateHandler(registry));
    }

    private static @Nullable ServerError legacyTasksUnavailable(DispatchContext context) {
        return context.requestMapper().supportsLegacyTaskAugmentation()
                ? null
                : ServerErrors.methodNotFound("Method not found");
    }

    private static @Nullable ServerError modernTasksOnly(DispatchContext context) {
        return context.requestMapper().supportsLegacyTaskAugmentation()
                ? ServerErrors.methodNotFound("Method not found")
                : null;
    }

    /** One answer for unknown and foreign tasks, so a caller cannot probe which ids exist. */
    private static ServerError taskNotFound(String action) {
        return ServerErrors.invalidParams("Failed to " + action + " task: Task not found");
    }

    private static void requireGate(@Nullable ServerError gate) {
        if (gate != null) {
            throw new RequestMappingException(gate);
        }
    }

    private record TasksListHandler(DefaultTaskRegistry registry)
            implements RpcMethodHandler<ProtocolRequestMapper.PageRequest, Object> {
        @Override
        public String method() {
            return "tasks/list";
        }

        @Override
        public ProtocolRequestMapper.PageRequest decode(DispatchContext context, @Nullable Object rawParams) {
            requireGate(legacyTasksUnavailable(context));
            return context.requestMapper().page(rawParams);
        }

        @Override
        public Object handle(DispatchContext context, ProtocolRequestMapper.PageRequest page) throws Exception {
            var connector = registry.taskConnector();
            if (connector == null || connector.list() == null) {
                return ServerErrors.methodNotFound("Method not found");
            }
            var request = TaskListRequest.builder()
                    .limit(registry.resolvePageLimit(page.limit()))
                    .cursor(page.cursor())
                    .meta(page.meta())
                    .build();
            var result = connector.list().apply(context, request);
            if (!result.cursorValid()) {
                return ServerErrors.invalidParams("Invalid cursor");
            }
            var snapshots = result.items().stream()
                    .filter(snapshot -> registry.visibleTo(snapshot.taskId(), context.sessionId()))
                    .map(registry::withDefaults)
                    .toList();
            return context.responseMapper().listTasksResult(snapshots, result.nextCursor());
        }
    }

    private record TasksGetHandler(DefaultTaskRegistry registry) implements RpcMethodHandler<TaskGetRequest, Object> {
        @Override
        public String method() {
            return "tasks/get";
        }

        @Override
        public TaskGetRequest decode(DispatchContext context, @Nullable Object rawParams) {
            requireGate(TasksExtension.requireDeclared(context));
            return context.requestMapper().taskGet(rawParams);
        }

        @Override
        public Object handle(DispatchContext context, TaskGetRequest request) throws Exception {
            var connector = registry.taskConnector();
            if (connector == null) {
                return ServerErrors.methodNotFound("Method not found");
            }
            if (!registry.visibleTo(request.taskId(), context.sessionId())) {
                return taskNotFound("retrieve");
            }
            final TaskSnapshot snapshot;
            try {
                snapshot = connector.get().apply(context, request);
            } catch (TaskNotFoundException e) {
                return taskNotFound("retrieve");
            }
            return context.responseMapper().getTaskResult(registry.publish(snapshot));
        }
    }

    private record TasksCancelHandler(DefaultTaskRegistry registry)
            implements RpcMethodHandler<TaskCancelRequest, Object> {
        @Override
        public String method() {
            return "tasks/cancel";
        }

        @Override
        public TaskCancelRequest decode(DispatchContext context, @Nullable Object rawParams) {
            requireGate(TasksExtension.requireDeclared(context));
            return context.requestMapper().taskCancel(rawParams);
        }

        @Override
        public Object handle(DispatchContext context, TaskCancelRequest request) throws Exception {
            var connector = registry.taskConnector();
            if (connector == null) {
                return ServerErrors.methodNotFound("Method not found");
            }
            if (!registry.visibleTo(request.taskId(), context.sessionId())) {
                return taskNotFound("cancel");
            }
            try {
                connector.cancel().apply(context, request);
            } catch (TaskNotFoundException e) {
                return taskNotFound("cancel");
            }
            if (!context.requestMapper().supportsLegacyTaskAugmentation()) {
                return context.responseMapper().emptyResult();
            }
            var getRequest = TaskGetRequest.builder()
                    .taskId(request.taskId())
                    .meta(request.meta())
                    .build();
            final TaskSnapshot snapshot;
            try {
                snapshot = connector.get().apply(context, getRequest);
            } catch (TaskNotFoundException e) {
                return taskNotFound("retrieve");
            }
            return context.responseMapper().cancelTaskResult(registry.publish(snapshot));
        }
    }

    private record TasksResultHandler(DefaultTaskRegistry registry)
            implements RpcMethodHandler<TaskAwaitResultRequest, Object> {
        @Override
        public String method() {
            return "tasks/result";
        }

        @Override
        public TaskAwaitResultRequest decode(DispatchContext context, @Nullable Object rawParams) {
            requireGate(legacyTasksUnavailable(context));
            return context.requestMapper().taskAwaitResult(rawParams);
        }

        @Override
        public Object handle(DispatchContext context, TaskAwaitResultRequest request) throws Exception {
            var connector = registry.taskConnector();
            if (connector == null || connector.awaitResult() == null) {
                return ServerErrors.methodNotFound("Method not found");
            }
            if (!registry.visibleTo(request.taskId(), context.sessionId())) {
                return taskNotFound("retrieve");
            }
            var snapshot = registry.publish(connector.awaitResult().apply(context, request));
            return context.responseMapper().getTaskPayloadResult(snapshot.result(), snapshot.taskId());
        }
    }

    private record TasksUpdateHandler(DefaultTaskRegistry registry)
            implements RpcMethodHandler<TaskUpdateRequest, Object> {
        @Override
        public String method() {
            return "tasks/update";
        }

        @Override
        public TaskUpdateRequest decode(DispatchContext context, @Nullable Object rawParams) {
            requireGate(modernTasksOnly(context));
            requireGate(TasksExtension.requireDeclared(context));
            return context.requestMapper().taskUpdate(rawParams);
        }

        @Override
        public Object handle(DispatchContext context, TaskUpdateRequest request) throws Exception {
            var connector = registry.taskConnector();
            if (connector == null) {
                return ServerErrors.methodNotFound("Method not found");
            }
            if (!registry.visibleTo(request.taskId(), context.sessionId())) {
                return taskNotFound("update");
            }
            try {
                connector.update().apply(context, request);
            } catch (TaskNotFoundException e) {
                return taskNotFound("update");
            }
            return context.responseMapper().emptyResult();
        }
    }
}
