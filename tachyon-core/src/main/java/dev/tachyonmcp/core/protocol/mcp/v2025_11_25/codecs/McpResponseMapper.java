/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.protocol.mcp.v2025_11_25.codecs;

import dev.tachyonmcp.api.json.JsonDocument;
import dev.tachyonmcp.api.server.domain.ContentBlock;
import dev.tachyonmcp.api.server.domain.FormInputRequest;
import dev.tachyonmcp.api.server.domain.InputRequest;
import dev.tachyonmcp.api.server.domain.LoggingLevel;
import dev.tachyonmcp.api.server.domain.PromptMessage;
import dev.tachyonmcp.api.server.domain.ResourceContents;
import dev.tachyonmcp.api.server.domain.RpcMethodRequest;
import dev.tachyonmcp.api.server.domain.ServerError;
import dev.tachyonmcp.api.server.domain.TaskResult;
import dev.tachyonmcp.api.server.domain.TextContent;
import dev.tachyonmcp.api.server.domain.UrlInputRequest;
import dev.tachyonmcp.api.server.features.completions.CompletionResult;
import dev.tachyonmcp.api.server.features.prompts.PromptDescriptor;
import dev.tachyonmcp.api.server.features.resources.ResourceDescriptor;
import dev.tachyonmcp.api.server.features.resources.ResourceTemplateDescriptor;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.api.server.features.tools.ToolDescriptor;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.api.server.features.tools.ToolResult.InputRequired;
import dev.tachyonmcp.api.server.features.tools.ToolResult.Success;
import dev.tachyonmcp.core.protocol.ProtocolResponseMapper;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.McpProtocol;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.CallToolResult;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.CompleteResult;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ElicitRequestFormParams;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ElicitRequestParams;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ElicitRequestURLParams;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.EmptyResult;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.GetPromptResult;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.InitializeResult;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ListPromptsResult;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ListResourceTemplatesResult;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ListResourcesResult;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ListTasksResult;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ListToolsResult;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.LoggingMessageNotificationParams;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ReadResourceResult;
import dev.tachyonmcp.core.server.domain.InitializeResponse;
import dev.tachyonmcp.core.server.json.JsonUtils;
import dev.tachyonmcp.core.transport.jsonrpc.JsonRpcCodec;
import dev.tachyonmcp.core.transport.jsonrpc.JsonRpcError;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** {@link ProtocolResponseMapper} for MCP 2025-11-25. */
public class McpResponseMapper implements ProtocolResponseMapper {

    private static final Object EMPTY = new EmptyResult(null, null);

    static {
        CodecRegistry.registerOverride(InputRequiredPayload.class, new InputRequiredPayloadCodec());
    }

    /** Default constructor, discovered via {@link java.util.ServiceLoader}. */
    public McpResponseMapper() {}

    @Override
    public boolean supports(String protocolName, String protocolVersion) {
        return "mcp".equalsIgnoreCase(protocolName) && McpProtocol.VERSION.equals(protocolVersion);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public String encode(Object value) {
        var codec = (Codec) CodecRegistry.codecFor(value.getClass());
        return codec == null
                ? ProtocolResponseMapper.super.encode(value)
                : JsonRpcCodec.writeAsString(gen -> codec.encode(gen, value));
    }

    @Override
    public Object emptyResult() {
        return EMPTY;
    }

    @Override
    public JsonRpcError error(ServerError error) {
        var code =
                switch (error.kind()) {
                    case PARSE_ERROR -> -32700;
                    case INVALID_REQUEST -> -32600;
                    case METHOD_NOT_FOUND -> -32601;
                    case INVALID_PARAMS -> -32602;
                    case INTERNAL_ERROR -> -32603;
                    case RESOURCE_NOT_FOUND -> -32002;
                    case HEADER_MISMATCH -> -32001;
                    case MISSING_REQUIRED_CLIENT_CAPABILITY -> -32003;
                    case UNSUPPORTED_PROTOCOL_VERSION -> -32004;
                };
        var data = error.data() != null ? JsonRpcCodec.writeValueAsString(error.data()) : null;
        return new JsonRpcError(code, error.message(), data);
    }

    @Override
    public Object completeResult(CompletionResult result) {
        return new CompleteResult(
                new CompleteResult.Completion(
                        List.copyOf(Objects.requireNonNull(result.values(), "values")),
                        result.total(),
                        result.hasMore()),
                JsonUtils.toJsonNodeMap(result.meta()),
                null);
    }

    @Override
    public Object initializeResult(InitializeResponse response) {
        var capsBuilder = ServerInfoMapper.toServerCapabilities(response.capabilities());
        if (response.registeredExtensions() != null
                && !response.registeredExtensions().isEmpty()) {
            capsBuilder.extensions(response.registeredExtensions().entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            Map.Entry::getKey, entry -> JsonUtils.parse(entry.getValue()))));
        }
        return new InitializeResult(
                response.protocolVersion(),
                capsBuilder.build(),
                ServerInfoMapper.toImplementation(response.serverIdentity()),
                response.instructions(),
                null,
                null);
    }

    @Override
    public Object listToolsResult(List<ToolDescriptor> tools, @Nullable String nextCursor) {
        var protocolTools = tools.stream().map(McpToolMapper::toTool).toList();
        return new ListToolsResult(protocolTools, null, nextCursor, null);
    }

    @Override
    public Object callToolResult(ToolResult result) {
        return switch (result) {
            case InputRequired ir ->
                new InputRequiredPayload(ir.inputRequests(), ir.requestState(), resolveMeta(result));
            case ToolResult.Task ignored -> throw new IllegalArgumentException("Task result requires task mapping");
            case ToolResult.Error er -> buildCallToolResult(er.content(), null, true, resolveMeta(result));
            case Success s -> wireSuccess(s, resolveMeta(result));
            default -> throw new IllegalArgumentException("Unsupported ToolResult: " + result.getClass());
        };
    }

    protected static @Nullable Map<String, JsonNode> resolveMeta(ToolResult result) {
        var meta = result.meta();
        return meta == null || meta.isEmpty() ? null : JsonUtils.toJsonNodeMap(meta);
    }

    private Object wireSuccess(Success s, @Nullable Map<String, JsonNode> meta) {
        return buildCallToolResult(s.content(), s.structuredValue(), null, meta);
    }

    /**
     * Builds a {@link CallToolResult} — the wire shape shared by {@code tools/call} responses and
     * {@code tasks/result} payloads (a task's terminal result is exactly what the tool call would
     * have returned).
     */
    private CallToolResult buildCallToolResult(
            List<ContentBlock> content,
            @Nullable Object structuredValue,
            @Nullable Boolean isError,
            @Nullable Map<String, JsonNode> meta) {
        var blocks = new java.util.ArrayList<>(
                content.stream().map(McpToolMapper::toProtocolContentBlock).toList());
        Map<String, JsonNode> structured = null;
        if (structuredValue != null) {
            JsonNode node =
                    switch (structuredValue) {
                        case JsonDocument document -> JsonUtils.parse(document);
                        case JsonNode n -> n;
                        default -> JsonUtils.parse(JsonUtils.writeString(structuredValue));
                    };
            // 2025-11-25's structuredContent is object-only on the wire; array/scalar values (only
            // representable from 2026-07-28 onward) fall back to the backwards-compat text block
            // below instead of being encoded as structuredContent.
            if (node.isObject()) {
                var objNode = (ObjectNode) node;
                var map = new LinkedHashMap<String, JsonNode>();
                for (var entry : objNode.properties()) {
                    map.put(entry.getKey(), entry.getValue());
                }
                structured = map;
            }
            // MCP: a tool returning structured content SHOULD also return the serialized JSON in a
            // text block (backwards-compat). Inject it when the handler supplied no text block.
            var hasText = content.stream().anyMatch(c -> c instanceof TextContent);
            if (!hasText || !node.isObject()) {
                blocks.add(McpToolMapper.toProtocolContentBlock(TextContent.of(node.toString())));
            }
        }
        return new CallToolResult(blocks, structured, isError, meta, null);
    }

    @Override
    public Object listResourcesResult(List<ResourceDescriptor> resources, @Nullable String nextCursor) {
        var protocolResources =
                resources.stream().map(McpResourceMapper::toResource).toList();
        return new ListResourcesResult(protocolResources, null, nextCursor, null);
    }

    @Override
    public Object listResourceTemplatesResult(List<ResourceTemplateDescriptor> templates, @Nullable String nextCursor) {
        var protocolTemplates =
                templates.stream().map(McpResourceMapper::toResourceTemplate).toList();
        return new ListResourceTemplatesResult(protocolTemplates, null, nextCursor, null);
    }

    @Override
    public Object readResourceResult(List<ResourceContents> contents) {
        var protocolContents = contents.stream()
                .map(ContentBlockMappers::toProtocolResourceContents)
                .toList();
        return new ReadResourceResult(protocolContents, null, null);
    }

    @Override
    public Object listPromptsResult(List<PromptDescriptor> prompts, @Nullable String nextCursor) {
        var protocolPrompts = prompts.stream().map(McpPromptMapper::toPrompt).toList();
        return new ListPromptsResult(protocolPrompts, null, nextCursor, null);
    }

    @Override
    public Object getPromptResult(
            @Nullable String description, List<PromptMessage> messages, @Nullable Map<String, Object> meta) {
        var protocolMessages =
                messages.stream().map(McpPromptMapper::toProtocolMessage).toList();
        return new GetPromptResult(description, protocolMessages, JsonUtils.toJsonNodeMap(meta), null);
    }

    @Override
    public Object listTasksResult(List<TaskSnapshot> entries, @Nullable String nextCursor) {
        var tasks = entries.stream().map(McpTaskMapper::toTaskProto).toList();
        return new ListTasksResult(tasks, null, nextCursor, null);
    }

    @Override
    public Object getTaskResult(TaskSnapshot snapshot) {
        return McpTaskMapper.toGetTaskResult(snapshot);
    }

    @Override
    public Object createTaskResult(TaskSnapshot snapshot) {
        return McpTaskMapper.toCreateTaskResult(snapshot);
    }

    @Override
    public Object cancelTaskResult(TaskSnapshot snapshot) {
        return McpTaskMapper.toCancelTaskResult(snapshot);
    }

    @Override
    public Object taskStatusNotificationParams(TaskSnapshot snapshot) {
        return McpTaskMapper.toStatusNotification(snapshot);
    }

    @Override
    public Object loggingMessageParams(LoggingLevel level, @Nullable String logger, @Nullable Object data) {
        return new LoggingMessageNotificationParams(
                LoggingLevelMapper.toProtocol(level),
                logger,
                JsonUtils.parseJsonNode(JsonRpcCodec.writeValueAsString(data)),
                null);
    }

    @Override
    public Object getTaskPayloadResult(@Nullable TaskResult result, String taskId) {
        return switch (result) {
            case null ->
                new CallToolResult(List.of(), null, null, JsonUtils.toJsonNodeMap(relatedTaskMeta(null, taskId)), null);
            case TaskResult.Completed c ->
                switch (c.result()) {
                    case ToolResult.Success s ->
                        buildCallToolResult(
                                s.content(),
                                s.structuredValue(),
                                null,
                                JsonUtils.toJsonNodeMap(relatedTaskMeta(s.meta(), taskId)));
                    case ToolResult.Error e ->
                        buildCallToolResult(
                                e.content(), null, true, JsonUtils.toJsonNodeMap(relatedTaskMeta(e.meta(), taskId)));
                    default ->
                        throw new IllegalStateException("unreachable: TaskResult.Completed cannot nest " + c.result());
                };
            case TaskResult.Failed f -> f.error();
        };
    }

    // Spec: every tasks/result response MUST carry io.modelcontextprotocol/related-task in _meta,
    // since the CallToolResult shape itself does not contain the task ID.
    private static Map<String, Object> relatedTaskMeta(@Nullable Map<String, Object> meta, String taskId) {
        var merged = meta != null ? new LinkedHashMap<>(meta) : new LinkedHashMap<String, Object>();
        merged.put("io.modelcontextprotocol/related-task", Map.of("taskId", taskId));
        return merged;
    }

    @Override
    public Object inputRequiredResult(
            Map<String, ? extends InputRequest> inputRequests,
            @Nullable String requestState,
            @Nullable Map<String, Object> meta) {
        return new InputRequiredPayload(inputRequests, requestState, JsonUtils.toJsonNodeMap(meta));
    }

    private record InputRequiredPayload(
            @Nullable Map<String, ? extends InputRequest> inputRequests,
            @Nullable String requestState,
            @Nullable Map<String, JsonNode> meta) {}

    private static final class InputRequiredPayloadCodec implements Codec<InputRequiredPayload> {

        @Override
        public InputRequiredPayload decode(JsonParser parser) {
            throw new UnsupportedOperationException("server-side only");
        }

        @Override
        public void encode(JsonGenerator gen, InputRequiredPayload value) {
            gen.writeStartObject();
            gen.writeStringProperty("resultType", "input_required");
            if (value.inputRequests() != null) {
                gen.writeObjectPropertyStart("inputRequests");
                for (var entry : value.inputRequests().entrySet()) {
                    gen.writeObjectPropertyStart(entry.getKey());
                    writeInputRequest(gen, entry.getValue());
                    gen.writeEndObject();
                }
                gen.writeEndObject();
            }
            if (value.requestState() != null) {
                gen.writeStringProperty("requestState", value.requestState());
            }
            if (value.meta() != null) {
                gen.writeObjectPropertyStart("_meta");
                CodecSupport.writeTreeEntries(gen, value.meta());
                gen.writeEndObject();
            }
            gen.writeEndObject();
        }

        private static void writeInputRequest(JsonGenerator gen, InputRequest req) {
            switch (req) {
                case RpcMethodRequest r -> {
                    gen.writeStringProperty("method", r.method());
                    if (r.params() != null) {
                        gen.writeName("params");
                        gen.writeRawValue(JsonUtils.writeString(r.params()));
                    } else {
                        gen.writeObjectPropertyStart("params");
                        gen.writeEndObject();
                    }
                }
                case FormInputRequest f -> {
                    gen.writeStringProperty("method", "elicitation/create");
                    gen.writeName("params");
                    var paramsCodec = CodecRegistry.codecFor(ElicitRequestParams.class);
                    paramsCodec.encode(
                            gen,
                            new ElicitRequestFormParams(
                                    null, f.message(), f.requestedSchema().json(), null, null));
                }
                case UrlInputRequest u -> {
                    gen.writeStringProperty("method", "elicitation/create");
                    gen.writeName("params");
                    var paramsCodec = CodecRegistry.codecFor(ElicitRequestParams.class);
                    paramsCodec.encode(
                            gen,
                            new ElicitRequestURLParams("url", u.message(), u.elicitationId(), u.url(), null, null));
                }
            }
        }
    }
}
