/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.protocol.mcp;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.json.JsonObject;
import dev.tachyonmcp.api.json.PayloadDeserializer;
import dev.tachyonmcp.api.server.domain.Args;
import dev.tachyonmcp.api.server.domain.LoggingLevel;
import dev.tachyonmcp.api.server.domain.ProgressToken;
import dev.tachyonmcp.api.server.domain.RequestId;
import dev.tachyonmcp.api.server.features.completions.CompletionRequest;
import dev.tachyonmcp.api.server.features.prompts.PromptRequest;
import dev.tachyonmcp.api.server.features.resources.ResourceRequest;
import dev.tachyonmcp.api.server.features.tasks.TaskAwaitResultRequest;
import dev.tachyonmcp.api.server.features.tasks.TaskCancelRequest;
import dev.tachyonmcp.api.server.features.tasks.TaskGetRequest;
import dev.tachyonmcp.api.server.features.tasks.TaskState;
import dev.tachyonmcp.api.server.features.tasks.TaskUpdateRequest;
import dev.tachyonmcp.api.server.features.tools.ToolRequest;
import dev.tachyonmcp.core.protocol.ProtocolRequestMapper;
import dev.tachyonmcp.core.protocol.RequestMappingException;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.codecs.LoggingLevelMapper;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.CallToolRequestParams;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.CancelledNotificationParams;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.CompleteRequestParams;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.GetPromptRequestParams;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.InitializeRequestParams;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.PaginatedRequestParams;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ReadResourceRequestParams;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.TaskStatus;
import dev.tachyonmcp.core.server.domain.ServerErrors;
import dev.tachyonmcp.core.server.json.JsonUtils;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Shared request mapping for MCP versions with backward-compatible request shapes.
 *
 * <p>Also parses the 2026-07-28 {@code inputResponses}/{@code requestState} fields (SEP-2322)
 * unconditionally: they're simply absent on 2025-11-25 wire payloads, so this is a no-op for that
 * version. Because these fields aren't part of this version's generated {@code *RequestParams}
 * models, they're read from the raw params map rather than through {@link #convert}.
 */
@InternalApi
public abstract class AbstractMcpRequestMapper implements ProtocolRequestMapper {

    private static final String META_LOG_LEVEL_KEY = "io.modelcontextprotocol/logLevel";
    private static final String META_CLIENT_CAPABILITIES_KEY = "io.modelcontextprotocol/clientCapabilities";

    @Override
    public PageRequest page(@Nullable Object params) {
        var node = asObject(params);
        var rawLimit = node.get("limit");
        var limit = rawLimit != null && rawLimit.isNumber() ? rawLimit.intValue() : 0;
        var paginated = convert(node, PaginatedRequestParams.class);
        return new PageRequest(limit, paginated.cursor(), optionalMap(node, "_meta", "Invalid _meta"));
    }

    @Override
    public ToolCallRequest callTool(@Nullable Object params, PayloadDeserializer payloadDeserializer) {
        return callTool(params, payloadDeserializer, true);
    }

    /**
     * Maps a tool call, optionally preserving MCP 2025-11-25's legacy task augmentation fields.
     *
     * <p>{@code task} is excluded before conversion and read from the raw params map instead, even
     * though the generated record declares it: 2026-07-28 payloads may carry a garbage or
     * differently-shaped {@code task} value there since that version ignores the field entirely
     * (see the class javadoc), and converting it as part of {@link CallToolRequestParams} would
     * fail on such payloads regardless of {@code legacyTaskAugmentation}.
     */
    protected final ToolCallRequest callTool(
            @Nullable Object params, PayloadDeserializer payloadDeserializer, boolean legacyTaskAugmentation) {
        var node = asObject(params);
        var callToolNode = node.has("task")
                ? JsonUtils.mapper().createObjectNode().setAll(node).without("task")
                : node;
        var callParams = convert(callToolNode, CallToolRequestParams.class);
        var meta = JsonUtils.toObjectMap(callParams._meta());
        var inputResponses = optionalMap(node, "inputResponses", "Invalid inputResponses");
        var requestState = optionalString(node, "requestState", "Invalid requestState");
        var task = legacyTaskAugmentation ? optionalMap(node, "task", "Invalid task metadata") : null;
        var ttl = task != null && task.get("ttl") instanceof Number value ? Duration.ofMillis(value.longValue()) : null;
        var request = ToolRequest.builder()
                .name(required(callParams.name(), "Missing tool name"))
                .arguments(Args.of(JsonUtils.toObjectMap(callParams.arguments()), payloadDeserializer))
                .meta(meta)
                .progressToken(progressToken(meta))
                .payloadDeserializer(payloadDeserializer)
                .inputResponses(inputResponses)
                .requestState(requestState)
                .build();
        return new ToolCallRequest(request, legacyTaskAugmentation && task != null, ttl);
    }

    @Override
    public PromptCallRequest getPrompt(@Nullable Object params) {
        var node = asObject(params);
        var promptParams = convert(node, GetPromptRequestParams.class);
        var inputResponses = optionalMap(node, "inputResponses", "Invalid inputResponses");
        var requestState = optionalString(node, "requestState", "Invalid requestState");
        var arguments = JsonUtils.toObjectMap(promptParams.arguments());
        return new PromptCallRequest(
                required(promptParams.name(), "Missing prompt name"),
                new PromptRequest(
                        arguments != null ? Args.of(arguments) : Args.empty(),
                        inputResponses,
                        requestState,
                        JsonUtils.toObjectMap(promptParams._meta())));
    }

    @Override
    public ResourceRequest readResource(@Nullable Object params) {
        var node = asObject(params);
        var resourceParams = convert(node, ReadResourceRequestParams.class);
        return ResourceRequest.builder()
                .uri(required(resourceParams.uri(), "Missing resource URI"))
                .meta(JsonUtils.toObjectMap(resourceParams._meta()))
                .inputResponses(optionalMap(node, "inputResponses", "Invalid inputResponses"))
                .requestState(optionalString(node, "requestState", "Invalid requestState"))
                .build();
    }

    @Override
    public CompletionCallRequest complete(@Nullable Object params) {
        var node = asObject(params);
        var ref = requiredObject(node, "ref", "Missing or invalid ref parameter");
        var argumentNode = requiredObject(node, "argument", "argument.name and argument.value are required");
        var argument = convert(argumentNode, CompleteRequestParams.Argument.class);
        var argumentName = required(argument.name(), "argument.name and argument.value are required");
        var argumentValue = required(argument.value(), "argument.name and argument.value are required");
        var refType = ref.get("type") != null && ref.get("type").isString()
                ? ref.get("type").stringValue()
                : null;
        final CompletionReference reference;
        if ("ref/prompt".equals(refType)) {
            reference =
                    new CompletionReference.Prompt(requiredString(ref, "name", "ref.name is required for ref/prompt"));
        } else if ("ref/resource".equals(refType)) {
            reference = new CompletionReference.Resource(
                    requiredString(ref, "uri", "ref.uri is required for ref/resource"));
        } else {
            throw invalidParams("Unknown ref.type: " + refType);
        }
        var context = optionalObject(node, "context", "Invalid context");
        var resolved = context != null
                ? stringMap(optionalObject(context, "arguments", "Invalid context.arguments"))
                : Map.<String, String>of();
        return new CompletionCallRequest(
                reference,
                CompletionRequest.builder()
                        .argumentName(argumentName)
                        .argumentValue(argumentValue)
                        .resolvedArguments(resolved)
                        .meta(optionalMap(node, "_meta", "Invalid _meta"))
                        .build());
    }

    @Override
    public String resourceUri(@Nullable Object params) {
        return requiredString(asObject(params), "uri", "Missing resource URI");
    }

    @Override
    public TaskGetRequest taskGet(@Nullable Object params) {
        var node = asObject(params);
        return TaskGetRequest.builder()
                .taskId(requiredString(node, "taskId", "Missing taskId"))
                .meta(optionalMap(node, "_meta", "Invalid _meta"))
                .build();
    }

    @Override
    public TaskCancelRequest taskCancel(@Nullable Object params) {
        var node = asObject(params);
        return TaskCancelRequest.builder()
                .taskId(requiredString(node, "taskId", "Missing taskId"))
                .meta(optionalMap(node, "_meta", "Invalid _meta"))
                .build();
    }

    @Override
    @SuppressWarnings("deprecation")
    public TaskAwaitResultRequest taskAwaitResult(@Nullable Object params) {
        var node = asObject(params);
        return TaskAwaitResultRequest.builder()
                .taskId(requiredString(node, "taskId", "Missing taskId"))
                .meta(optionalMap(node, "_meta", "Invalid _meta"))
                .build();
    }

    @Override
    public TaskUpdateRequest taskUpdate(@Nullable Object params) {
        final var node = asObject(params);
        final var taskId = requiredString(node, "taskId", "Missing taskId");
        final var responses = requiredMap(node, "inputResponses", "Missing inputResponses");
        return TaskUpdateRequest.builder()
                .taskId(taskId)
                .inputResponses(responses)
                .meta(optionalMap(node, "_meta", "Invalid _meta"))
                .build();
    }

    @Override
    public LoggingLevel loggingLevel(@Nullable Object params) {
        var value = requiredString(asObject(params), "level", "Missing level parameter");
        try {
            return LoggingLevelMapper.toDomain(
                    dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.LoggingLevel.fromValue(value));
        } catch (IllegalArgumentException e) {
            throw invalidParams("Invalid logging level: " + value);
        }
    }

    @Override
    public InitializeRequest initialize(@Nullable Object params) {
        var initParams = convert(asObject(params), InitializeRequestParams.class);
        var capabilities = initParams.capabilities();
        var extensions = capabilities != null ? JsonUtils.toObjectMap(capabilities.extensions()) : null;
        return new InitializeRequest(mapExtensions(extensions));
    }

    @Override
    public Map<String, JsonObject> declaredExtensions(@Nullable Object params) {
        var node = asObject(params);
        if (!(node.get("_meta") instanceof ObjectNode meta)) return Map.of();
        if (!(meta.get(META_CLIENT_CAPABILITIES_KEY) instanceof ObjectNode capabilities)) return Map.of();
        if (!(capabilities.get("extensions") instanceof ObjectNode extensions)) return Map.of();
        return mapExtensions(JsonUtils.toObjectMap(extensions));
    }

    /** Maps a raw {@code extensions} object (id -> settings) to the domain {@code Map<String, JsonObject>} shape. */
    private static Map<String, JsonObject> mapExtensions(@Nullable Map<?, ?> extensions) {
        if (extensions == null || extensions.isEmpty()) return Map.of();
        var mapped = new LinkedHashMap<String, JsonObject>();
        stringKeyed(extensions)
                .forEach((key, value) -> mapped.put(
                        key, value instanceof Map<?, ?> map ? JsonObject.of(stringKeyed(map)) : JsonObject.empty()));
        return Map.copyOf(mapped);
    }

    @Override
    public @Nullable LoggingLevel permittedLogLevel(@Nullable Object params) {
        var meta = optionalObject(asObject(params), "_meta", "Invalid _meta");
        var value = meta != null ? meta.get(META_LOG_LEVEL_KEY) : null;
        if (value == null || !value.isString()) return null;
        try {
            return LoggingLevel.fromValue(value.stringValue());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @Override
    public @Nullable CancellationRequest cancellation(@Nullable Object params) {
        var node = asObject(params);
        var rawId = node.get("requestId");
        if (rawId == null || !(rawId.isString() || rawId.isNumber())) return null;
        var cancelParams = convert(node, CancelledNotificationParams.class);
        var id = rawId.isString() ? RequestId.of(rawId.stringValue()) : RequestId.of(rawId.numberValue());
        return new CancellationRequest(id, cancelParams.reason());
    }

    @Override
    public @Nullable TaskStatusRequest taskStatus(@Nullable Object params) {
        var node = asObject(params);
        var rawTaskId = node.get("taskId");
        if (rawTaskId == null || !rawTaskId.isString()) return null;
        var rawStatus = node.get("status");
        var state = rawStatus != null && rawStatus.isString()
                ? toTaskState(parseTaskStatus(rawStatus.stringValue()))
                : TaskState.WORKING;
        var rawMessage = node.get("statusMessage");
        var message = rawMessage != null && rawMessage.isString() ? rawMessage.stringValue() : null;
        return new TaskStatusRequest(rawTaskId.stringValue(), state, message);
    }

    /** Parses a {@code status} string via the generated enum, defaulting unknown values to {@code WORKING}. */
    private static TaskStatus parseTaskStatus(String value) {
        try {
            return TaskStatus.fromValue(value);
        } catch (IllegalArgumentException e) {
            return TaskStatus.WORKING;
        }
    }

    private static TaskState toTaskState(TaskStatus status) {
        return switch (status) {
            case INPUT_REQUIRED -> TaskState.INPUT_REQUIRED;
            case COMPLETED -> TaskState.COMPLETED;
            case FAILED -> TaskState.FAILED;
            case CANCELLED -> TaskState.CANCELLED;
            case WORKING -> TaskState.WORKING;
        };
    }

    /**
     * Narrows a raw {@code params} payload to the object node the request shapes are read from.
     *
     * <p>{@link dev.tachyonmcp.core.transport.jsonrpc.JsonRpcCodec} parses {@code params} straight
     * into a tree, so the generated codecs decode it without an intermediate map. A {@link Map}
     * payload is still accepted for callers that build requests in memory.
     */
    protected ObjectNode asObject(@Nullable Object params) {
        switch (params) {
            case null -> {
                return JsonUtils.mapper().createObjectNode();
            }
            case ObjectNode node -> {
                return node;
            }
            case Map<?, ?> map -> {
                return JsonUtils.toObjectNode(stringKeyed(map));
            }
            case JsonNode ignored -> {}
            default -> {
                try {
                    if (JsonUtils.mapper().valueToTree(params) instanceof ObjectNode node) return node;
                } catch (JacksonException e) {
                    throw invalidParams("Invalid params: " + e.getOriginalMessage());
                }
            }
        }
        throw invalidParams("Params must be an object");
    }

    /**
     * Decodes a params node into a generated model with this version's codec.
     *
     * <p>Implemented per protocol version because codec interfaces and registries are generated per
     * version.
     *
     * @param <T>  the model type
     * @param node the params object
     * @param type the model to decode into
     * @return the decoded model
     * @throws RequestMappingException with {@code invalid_params} if the payload does not match
     */
    protected abstract <T> T convert(ObjectNode node, Class<T> type);

    /**
     * Runs a version's codec over a params node, mapping decode failures to {@code invalid_params}.
     *
     * @param <T>     the model type
     * @param node    the params object
     * @param decoder the version's codec decode method
     * @return the decoded model
     */
    protected static <T> T decodeParams(ObjectNode node, Function<JsonParser, T> decoder) {
        try (var parser = node.traverse(ObjectReadContext.empty())) {
            parser.nextToken();
            return decoder.apply(parser);
        } catch (JacksonException e) {
            throw invalidParams(e.getOriginalMessage());
        }
    }

    /** Requires a field extracted from a generated model to be non-null, or throws {@code invalid_params}. */
    private static <T> T required(@Nullable T value, String message) {
        if (value == null) throw invalidParams(message);
        return value;
    }

    private static Map<String, Object> stringKeyed(Map<?, ?> source) {
        var result = new LinkedHashMap<String, Object>();
        source.forEach((key, value) -> {
            if (key instanceof String text) result.put(text, value);
        });
        return result;
    }

    private static String requiredString(JsonNode node, String key, String message) {
        var value = node.get(key);
        if (value != null && value.isString()) return value.stringValue();
        throw invalidParams(message);
    }

    private static @Nullable String optionalString(JsonNode node, String key, String message) {
        var value = node.get(key);
        if (value == null || value.isNull()) return null;
        if (value.isString()) return value.stringValue();
        throw invalidParams(message);
    }

    private static ObjectNode requiredObject(JsonNode node, String key, String message) {
        var value = node.get(key);
        if (value instanceof ObjectNode object) return object;
        throw invalidParams(message);
    }

    private static @Nullable ObjectNode optionalObject(JsonNode node, String key, String message) {
        var value = node.get(key);
        if (value == null || value.isNull()) return null;
        if (value instanceof ObjectNode object) return object;
        throw invalidParams(message);
    }

    /** Reads a nested object as the {@code Map<String, Object>} shape the domain requests expect. */
    private static Map<String, Object> requiredMap(JsonNode node, String key, String message) {
        var value = optionalMap(node, key, message);
        if (value != null) return value;
        throw invalidParams(message);
    }

    private static @Nullable Map<String, Object> optionalMap(JsonNode node, String key, String message) {
        var value = optionalObject(node, key, message);
        return value == null ? null : JsonUtils.toObjectMap(value);
    }

    private static Map<String, String> stringMap(@Nullable JsonNode node) {
        if (node == null || node.isEmpty()) return Map.of();
        var result = new LinkedHashMap<String, String>();
        node.properties().forEach(entry -> {
            if (entry.getValue().isString())
                result.put(entry.getKey(), entry.getValue().stringValue());
            else throw invalidParams("context.arguments values must be strings");
        });
        return result;
    }

    private static @Nullable ProgressToken progressToken(@Nullable Map<String, Object> meta) {
        if (meta == null) return null;
        var value = meta.get("progressToken");
        if (value instanceof String text) return ProgressToken.of(text);
        if (value instanceof Number number) return ProgressToken.of(number);
        return null;
    }

    /** Creates the protocol error used when request parameters cannot be mapped. */
    protected static RequestMappingException invalidParams(String message) {
        return new RequestMappingException(ServerErrors.invalidParams(message));
    }
}
