/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.features.tools;

import static dev.tachyonmcp.core.server.domain.ServerErrors.fromUnhandledException;
import static dev.tachyonmcp.core.server.domain.ServerErrors.internalError;
import static dev.tachyonmcp.core.server.domain.ServerErrors.invalidParams;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.json.JsonDocument;
import dev.tachyonmcp.api.json.JsonSchema;
import dev.tachyonmcp.api.json.JsonSchemaValidator;
import dev.tachyonmcp.api.json.PayloadDeserializer;
import dev.tachyonmcp.api.json.PayloadSerializer;
import dev.tachyonmcp.api.json.SchemaValidationError;
import dev.tachyonmcp.api.server.domain.LoggingLevel;
import dev.tachyonmcp.api.server.domain.ServerError;
import dev.tachyonmcp.api.server.features.tasks.TaskSupport;
import dev.tachyonmcp.api.server.features.tools.ToolRequest;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.protocol.ProtocolRequestMapper;
import dev.tachyonmcp.core.server.RpcMethodHandler;
import dev.tachyonmcp.core.server.features.tasks.TasksExtension;
import dev.tachyonmcp.core.server.internal.HandlerFutures;
import dev.tachyonmcp.core.server.json.JsonUtils;
import dev.tachyonmcp.core.server.observability.CapturedPayload;
import dev.tachyonmcp.core.server.session.DispatchContext;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** JSON-RPC adapters for tool operations. */
@InternalApi
public final class ToolMethodHandlers {

    private ToolMethodHandlers() {}

    public static void register(
            Map<String, RpcMethodHandler<?, ?>> handlers,
            DefaultToolRegistry registry,
            JsonSchemaValidator inputValidator,
            JsonSchemaValidator outputValidator,
            PayloadSerializer payloadSerializer,
            PayloadDeserializer payloadDeserializer) {
        handlers.put("tools/list", new ToolsListHandler(registry));
        handlers.put(
                "tools/call",
                new ToolsCallHandler(
                        registry, inputValidator, outputValidator, payloadSerializer, payloadDeserializer));
    }

    private record ToolsListHandler(DefaultToolRegistry registry)
            implements RpcMethodHandler<ProtocolRequestMapper.PageRequest, Object> {

        @Override
        public String method() {
            return "tools/list";
        }

        @Override
        public ProtocolRequestMapper.PageRequest decode(DispatchContext context, @Nullable Object rawParams) {
            return context.requestMapper().page(rawParams);
        }

        @Override
        public Object handle(DispatchContext context, ProtocolRequestMapper.PageRequest page) {
            var paginated = registry.list(page.limit(), page.cursor(), descriptor -> {
                var extensionId = descriptor.extensionId();
                return extensionId == null || context.isExtensionEnabled(extensionId);
            });
            if (!paginated.cursorValid()) return invalidParams("Invalid cursor");
            return context.responseMapper().listToolsResult(paginated.items(), paginated.nextCursor());
        }
    }

    private record ToolsCallHandler(
            DefaultToolRegistry registry,
            JsonSchemaValidator inputValidator,
            JsonSchemaValidator outputValidator,
            PayloadSerializer payloadSerializer,
            PayloadDeserializer payloadDeserializer)
            implements RpcMethodHandler<ProtocolRequestMapper.ToolCallRequest, Object> {

        private static final Logger logger = LoggerFactory.getLogger(ToolsCallHandler.class);

        @Override
        public String method() {
            return "tools/call";
        }

        @Override
        public ProtocolRequestMapper.ToolCallRequest decode(DispatchContext context, @Nullable Object rawParams) {
            return context.requestMapper().callTool(rawParams, payloadDeserializer);
        }

        @Override
        public @Nullable Object handle(DispatchContext context, ProtocolRequestMapper.ToolCallRequest mapped)
                throws Exception {
            return HandlerFutures.joinInterruptible(handleAsync(context, mapped));
        }

        @Override
        public CompletionStage<Object> handleAsync(
                DispatchContext context, ProtocolRequestMapper.ToolCallRequest mapped) {
            var request = mapped.request();
            if (request.name().length() > DefaultToolRegistry.MAX_NAME_LENGTH) {
                return CompletableFuture.completedFuture(invalidParams("Tool name exceeds maximum length (SEP-986)"));
            }

            var handler = registry.get(request.name());
            if (handler == null) {
                return CompletableFuture.completedFuture(invalidParams("Unknown tool: " + request.name()));
            }
            var observation = context.observation();
            observation.info().target(handler.descriptor().name());
            if (observation.active()
                    && context.engine()
                            .config()
                            .observability()
                            .payloadCapture()
                            .requestArgs()) {
                var maxBytes = context.engine()
                        .config()
                        .observability()
                        .payloadCapture()
                        .maxBytes();
                observation
                        .info()
                        .requestPayload(
                                CapturedPayload.capture(request.arguments().json(), maxBytes));
            }
            var extensionId = handler.descriptor().extensionId();
            if (extensionId != null && !context.isExtensionEnabled(extensionId)) {
                return CompletableFuture.completedFuture(invalidParams("Unknown tool: " + request.name()));
            }

            var validationError = validateInput(handler.descriptor().inputSchema(), request);
            if (validationError != null) return CompletableFuture.completedFuture(invalidParams(validationError));

            var configuredTaskSupport = handler.descriptor().taskSupport();
            var taskSupport = configuredTaskSupport != null ? configuredTaskSupport : TaskSupport.FORBIDDEN;

            var taskError = validateTaskRequest(context, mapped, taskSupport);
            if (taskError != null) return CompletableFuture.completedFuture(taskError);

            sendLogging(context, request.name(), "started");
            return HandlerFutures.invokeAndMap(
                    "Tool '" + request.name() + "' returned a null CompletionStage",
                    () -> handler.handleAsync(context, request),
                    context.engine().executor(),
                    (toolResult, cause) -> {
                        if (cause != null) return handlerError(context, request.name(), cause);
                        sendLogging(context, request.name(), "completed");
                        return mapResult(
                                context,
                                mapped,
                                taskSupport,
                                handler.descriptor().outputSchema(),
                                toolResult);
                    });
        }

        private @Nullable Object validateTaskRequest(
                DispatchContext context, ProtocolRequestMapper.ToolCallRequest mapped, TaskSupport taskSupport) {
            if (context.requestMapper().supportsLegacyTaskAugmentation()) {
                if (taskSupport == TaskSupport.FORBIDDEN && mapped.taskAugmented()) {
                    return invalidParams("Task augmentation not supported for this tool");
                }
                if (taskSupport == TaskSupport.REQUIRED && !mapped.taskAugmented()) {
                    return invalidParams("Task augmentation required for this tool");
                }
                return null;
            }
            return taskSupport == TaskSupport.REQUIRED ? TasksExtension.requireDeclared(context) : null;
        }

        private Object mapResult(
                DispatchContext context,
                ProtocolRequestMapper.ToolCallRequest mapped,
                TaskSupport taskSupport,
                @Nullable JsonSchema outputSchema,
                ToolResult result) {
            if (result instanceof ToolResult.Task task) {
                if (taskSupport == TaskSupport.FORBIDDEN) {
                    return internalError("Task-producing tool is configured to forbid task augmentation");
                }
                if (context.requestMapper().supportsLegacyTaskAugmentation() && !mapped.taskAugmented()) {
                    return internalError("Task-producing tool returned a task for a non-task request");
                }
                if (!context.engine().tasksRegistry().executionConfigured()) {
                    return internalError("Task-producing tool requires a configured TaskConnector");
                }
                var missingCapability = TasksExtension.requireDeclared(context);
                if (!context.requestMapper().supportsLegacyTaskAugmentation() && missingCapability != null) {
                    return missingCapability;
                }
                var snapshot = context.engine()
                        .tasksRegistry()
                        .create(
                                task.snapshot(),
                                context.sessionId(),
                                mapped.request().progressToken());
                if (snapshot == null) {
                    return internalError("Task-producing tool returned a task owned by another session");
                }
                context.observation().markTaskHandoff(snapshot.taskId());
                return context.responseMapper().createTaskResult(snapshot);
            }
            if (taskSupport == TaskSupport.REQUIRED || mapped.taskAugmented()) {
                return internalError("Task-producing tool returned a non-task result");
            }
            var prepared = prepareResult(outputSchema, result);
            if (prepared instanceof ToolResult.Error) {
                context.observation().markPayloadFailure();
            }
            var wireResult = context.responseMapper().callToolResult(prepared);
            captureResponseContent(context, wireResult);
            return wireResult;
        }

        private static void captureResponseContent(DispatchContext context, Object wireResult) {
            var observation = context.observation();
            if (!observation.active()) return;
            var payloadCapture = context.engine().config().observability().payloadCapture();
            if (!payloadCapture.responseContent()) return;
            var json = context.responseMapper().encode(wireResult);
            observation.info().responsePayload(CapturedPayload.capture(json, payloadCapture.maxBytes()));
        }

        private ToolResult prepareResult(@Nullable JsonSchema outputSchema, ToolResult result) {
            var serialized = JsonUtils.serializeStructured(result, payloadSerializer);
            var validationError = validateOutput(outputSchema, serialized);
            if (validationError == null) return serialized;
            var error = ToolResult.error(validationError);
            var meta = serialized.meta();
            return meta == null || meta.isEmpty() ? error : error.withMeta(meta);
        }

        private @Nullable String validateInput(@Nullable JsonSchema schema, ToolRequest request) {
            if (schema == null) return null;
            var errors = inputValidator.validate(
                    schema, JsonDocument.of(request.arguments().json()));
            return errors.isEmpty() ? null : SchemaValidationError.join(errors);
        }

        private @Nullable String validateOutput(@Nullable JsonSchema schema, ToolResult result) {
            if (schema == null || outputValidator == JsonSchemaValidator.noop()) return null;
            if (!(result instanceof ToolResult.Success success) || success.structuredValue() == null) return null;
            var value = success.structuredValue();
            var json = value instanceof JsonDocument document ? document.json() : payloadSerializer.serialize(value);
            var errors = outputValidator.validate(schema, JsonDocument.of(json));
            return errors.isEmpty() ? null : SchemaValidationError.join(errors);
        }

        private Object handlerError(DispatchContext context, String name, Throwable cause) {
            if (cause instanceof CancellationException) {
                logger.debug("Tool call cancelled for '{}'", name);
                return internalError("Tool call cancelled");
            }
            var error = fromUnhandledException(cause, "Tool handler failed");
            if (error.kind() == ServerError.Kind.INVALID_PARAMS) {
                logger.debug("Tool handler rejected invalid params for '{}'", name);
            } else if (error.kind() == ServerError.Kind.INTERNAL_ERROR) {
                logger.error("Tool handler error for '{}'", name, cause);
            }
            context.captureExceptionCause(cause);
            return error;
        }

        private void sendLogging(DispatchContext context, String toolName, String status) {
            context.notifications()
                    .log(LoggingLevel.DEBUG, "tachyon.tools", Map.of("tool", toolName, "status", status));
        }
    }
}
