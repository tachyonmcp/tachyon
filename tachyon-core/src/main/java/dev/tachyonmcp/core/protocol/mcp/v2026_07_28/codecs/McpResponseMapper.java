/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.protocol.mcp.v2026_07_28.codecs;

import dev.tachyonmcp.api.json.JsonDocument;
import dev.tachyonmcp.api.json.JsonObject;
import dev.tachyonmcp.api.json.JsonSchema;
import dev.tachyonmcp.api.server.config.ServerIdentity;
import dev.tachyonmcp.api.server.domain.Annotations;
import dev.tachyonmcp.api.server.domain.ContentBlock;
import dev.tachyonmcp.api.server.domain.FormInputRequest;
import dev.tachyonmcp.api.server.domain.InputRequest;
import dev.tachyonmcp.api.server.domain.LoggingLevel;
import dev.tachyonmcp.api.server.domain.PromptMessage;
import dev.tachyonmcp.api.server.domain.RequestId;
import dev.tachyonmcp.api.server.domain.RpcMethodRequest;
import dev.tachyonmcp.api.server.domain.ServerCapabilities;
import dev.tachyonmcp.api.server.domain.ServerError;
import dev.tachyonmcp.api.server.domain.TaskResult;
import dev.tachyonmcp.api.server.domain.TextContent;
import dev.tachyonmcp.api.server.domain.ToolAnnotations;
import dev.tachyonmcp.api.server.domain.UrlInputRequest;
import dev.tachyonmcp.api.server.features.completions.CompletionResult;
import dev.tachyonmcp.api.server.features.prompts.PromptDescriptor;
import dev.tachyonmcp.api.server.features.resources.ResourceDescriptor;
import dev.tachyonmcp.api.server.features.resources.ResourceTemplateDescriptor;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.api.server.features.tools.ToolDescriptor;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.protocol.ProtocolRequestMapper.SubscriptionListenRequest;
import dev.tachyonmcp.core.protocol.codec.Codec;
import dev.tachyonmcp.core.protocol.codec.CodecSupport;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.McpProtocol;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.BlobResourceContents;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.CallToolResult;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.CompleteResult;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.DiscoverResult;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.ElicitRequest;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.ElicitRequestFormParams;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.ElicitRequestURLParams;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.EmptyResult;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.GetPromptResult;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.Implementation;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.InputRequests;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.InputRequiredResult;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.ListPromptsResult;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.ListResourceTemplatesResult;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.ListResourcesResult;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.ListToolsResult;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.LoggingMessageNotificationParams;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.NotificationParams;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.Prompt;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.PromptArgument;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.ReadResourceResult;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.Resource;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.ResourceContents;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.ResourceTemplate;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.ResourceUpdatedNotificationParams;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.SubscriptionFilter;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.SubscriptionsAcknowledgedNotificationParams;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.SubscriptionsListenResult;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.TextResourceContents;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.Tool;
import dev.tachyonmcp.core.server.json.JsonUtils;
import dev.tachyonmcp.core.transport.jsonrpc.JsonRpcCodec;
import dev.tachyonmcp.core.transport.jsonrpc.JsonRpcError;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Maps the modern MCP discovery and empty response shapes.
 */
public final class McpResponseMapper extends dev.tachyonmcp.core.protocol.mcp.v2025_11_25.codecs.McpResponseMapper {

    private static final String COMPLETE = "complete";
    private static final String PRIVATE = "private";
    private static final String PUBLIC = "public";
    private static final String INPUT_REQUIRED = "input_required";
    private static final String SUBSCRIPTION_ID_META_KEY = "io.modelcontextprotocol/subscriptionId";

    @Override
    public boolean supports(String protocolName, String protocolVersion) {
        return "mcp".equalsIgnoreCase(protocolName) && McpProtocol.VERSION.equals(protocolVersion);
    }

    /**
     * Encodes with this version's codecs, falling back to 2025-11-25's for the responses inherited
     * unchanged from the superclass ({@code initialize}, {@code tasks/list}, {@code tasks/result}),
     * which are still built from that version's models.
     */
    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public String encode(Object value) {
        var codec = (Codec) CodecRegistry.codecFor(value.getClass());
        return codec == null ? super.encode(value) : JsonRpcCodec.writeAsString(gen -> codec.encode(gen, value));
    }

    @Override
    public Object emptyResult() {
        return new EmptyResult(null, COMPLETE, null);
    }

    @Override
    public JsonRpcError error(ServerError error) {
        var mapped = super.error(error);
        var code =
                switch (error.kind()) {
                    case RESOURCE_NOT_FOUND -> -32602;
                    case HEADER_MISMATCH -> -32020;
                    case MISSING_REQUIRED_CLIENT_CAPABILITY -> -32021;
                    case UNSUPPORTED_PROTOCOL_VERSION -> -32022;
                    default -> mapped.code();
                };
        var httpStatus =
                switch (error.kind()) {
                    case INVALID_PARAMS,
                            HEADER_MISMATCH,
                            MISSING_REQUIRED_CLIENT_CAPABILITY,
                            UNSUPPORTED_PROTOCOL_VERSION -> 400;
                    case METHOD_NOT_FOUND -> 404;
                    default -> 200;
                };
        return new JsonRpcError(code, mapped.message(), mapped.data(), httpStatus);
    }

    @Override
    public Object discoverResult(
            List<String> supportedVersions,
            ServerCapabilities capabilities,
            ServerIdentity serverIdentity,
            Map<String, JsonObject> registeredExtensions) {
        var capsBuilder = ServerInfoMapper.toServerCapabilities(capabilities);
        if (!registeredExtensions.isEmpty()) {
            var extensions = JsonNodeFactory.instance.objectNode();
            registeredExtensions.forEach((id, settings) -> extensions.set(id, JsonUtils.parse(settings)));
            capsBuilder.extensions(extensions);
        }
        var serverInfo = encodeToTree(Implementation.class, ServerInfoMapper.toImplementation(serverIdentity));
        var meta = JsonNodeFactory.instance.objectNode();
        meta.set("io.modelcontextprotocol/serverInfo", serverInfo);
        // The schema models server identity only via the optional
        // _meta["io.modelcontextprotocol/serverInfo"] key (see `meta` above), but the pinned
        // conformance suite still requires a top-level `serverInfo` field too. `Result` permits
        // arbitrary extra keys (`[key: string]: unknown`), so mirror it there via
        // additionalProperties for conformance, in addition to the spec-correct `_meta` location.
        var additionalProperties = JsonNodeFactory.instance.objectNode();
        additionalProperties.set("serverInfo", serverInfo);
        return new DiscoverResult(
                supportedVersions,
                capsBuilder.build(),
                serverIdentity.instructions(),
                meta,
                COMPLETE,
                0,
                PUBLIC,
                additionalProperties);
    }

    // Caching hints (SEP-2549): fixed ttlMs=0/cacheScope="public" policy except for
    // metadata-sensitive annotated resource reads, which require private caching.

    @Override
    public Object completeResult(CompletionResult result) {
        return new CompleteResult(
                new CompleteResult.Completion(List.copyOf(result.values()), result.total(), result.hasMore()),
                JsonUtils.toObjectTree(result.meta()),
                COMPLETE,
                null);
    }

    @Override
    public Object listToolsResult(List<ToolDescriptor> tools, @Nullable String nextCursor) {
        var protocolTools = tools.stream().map(McpResponseMapper::toTool).toList();
        return new ListToolsResult(protocolTools, null, COMPLETE, nextCursor, 0, PUBLIC, null);
    }

    @Override
    public Object listResourcesResult(List<ResourceDescriptor> resources, @Nullable String nextCursor) {
        var protocolResources =
                resources.stream().map(McpResponseMapper::toResource).toList();
        return new ListResourcesResult(protocolResources, null, COMPLETE, nextCursor, 0, PUBLIC, null);
    }

    @Override
    public Object listResourceTemplatesResult(List<ResourceTemplateDescriptor> templates, @Nullable String nextCursor) {
        var protocolTemplates =
                templates.stream().map(McpResponseMapper::toResourceTemplate).toList();
        return new ListResourceTemplatesResult(protocolTemplates, null, COMPLETE, nextCursor, 0, PUBLIC, null);
    }

    @Override
    public Object readResourceResult(
            List<dev.tachyonmcp.api.server.domain.ResourceContents> contents, boolean privateCaching) {
        var protocolContents =
                contents.stream().map(McpResponseMapper::toResourceContents).toList();
        return new ReadResourceResult(protocolContents, null, COMPLETE, 0, privateCaching ? PRIVATE : PUBLIC, null);
    }

    @Override
    public Object listPromptsResult(List<PromptDescriptor> prompts, @Nullable String nextCursor) {
        var protocolPrompts = prompts.stream().map(McpResponseMapper::toPrompt).toList();
        return new ListPromptsResult(protocolPrompts, null, COMPLETE, nextCursor, 0, PUBLIC, null);
    }

    @Override
    public Object callToolResult(ToolResult result) {
        return switch (result) {
            case ToolResult.InputRequired ir ->
                inputRequired(ir.inputRequests(), ir.requestState(), resolveMeta(result));
            case ToolResult.Task ignored -> throw new IllegalArgumentException("Task result requires task mapping");
            case ToolResult.Error error -> buildCallToolResult(error.content(), null, true, resolveMeta(result));
            case ToolResult.Success success ->
                buildCallToolResult(success.content(), success.structuredValue(), null, resolveMeta(result));
            default -> throw new IllegalArgumentException("Unsupported ToolResult: " + result.getClass());
        };
    }

    @Override
    public Object inputRequiredResult(
            Map<String, ? extends InputRequest> inputRequests,
            @Nullable String requestState,
            @Nullable Map<String, Object> meta) {
        return inputRequired(inputRequests, requestState, JsonUtils.toObjectTree(meta));
    }

    private static Object inputRequired(
            @Nullable Map<String, ? extends InputRequest> inputRequests,
            @Nullable String requestState,
            @Nullable ObjectNode meta) {
        return new InputRequiredResult(encodedInputRequests(inputRequests), requestState, meta, INPUT_REQUIRED, null);
    }

    /**
     * Encodes each domain {@link InputRequest} into a generated 2026-07-28 {@link InputRequest}
     * payload (or, for generic RPC methods, a minimal {@code method}/{@code params} object), so the
     * generated {@link InputRequiredResult}/{@link InputRequests} codecs can serialize the
     * {@code input_required} response without hand-written mapping.
     */
    private static @Nullable InputRequests encodedInputRequests(
            @Nullable Map<String, ? extends InputRequest> inputRequests) {
        if (inputRequests == null) {
            return null;
        }
        var encoded = JsonNodeFactory.instance.objectNode();
        for (var entry : inputRequests.entrySet()) {
            encoded.set(entry.getKey(), encodeInputRequest(entry.getValue()));
        }
        return new InputRequests(encoded);
    }

    private static JsonNode encodeInputRequest(InputRequest req) {
        return switch (req) {
            case RpcMethodRequest r -> {
                var fields = new LinkedHashMap<String, Object>();
                fields.put("method", r.method());
                fields.put("params", Objects.requireNonNullElseGet(r.params(), Map::of));
                yield JsonUtils.toObjectNode(fields);
            }
            case FormInputRequest f ->
                encodeToTree(
                        ElicitRequest.class,
                        new ElicitRequest(
                                "elicitation/create",
                                new ElicitRequestFormParams(
                                        null, f.message(), f.requestedSchema().json())));
            case UrlInputRequest u ->
                encodeToTree(
                        ElicitRequest.class,
                        // 2026-07-28 removed the `elicitationId` field; only mode/message/url are serialized.
                        new ElicitRequest(
                                "elicitation/create", new ElicitRequestURLParams("url", u.message(), u.url())));
        };
    }

    @Override
    public Object getPromptResult(
            @Nullable String description, List<PromptMessage> messages, @Nullable Map<String, Object> meta) {
        return new GetPromptResult(
                description,
                messages.stream().map(McpResponseMapper::toPromptMessage).toList(),
                JsonUtils.toObjectTree(meta),
                COMPLETE,
                null);
    }

    @Override
    public Object getTaskResult(TaskSnapshot snapshot) {
        return McpTaskMapper.toGetTaskResult(
                snapshot, taskResultNode(snapshot), taskErrorNode(snapshot), inputRequestsNode(snapshot));
    }

    @Override
    public Object createTaskResult(TaskSnapshot snapshot) {
        return McpTaskMapper.toCreateTaskResult(snapshot);
    }

    @Override
    public Object cancelTaskResult(TaskSnapshot snapshot) {
        return McpTaskMapper.toCancelTaskResult();
    }

    @Override
    public Object taskStatusNotificationParams(TaskSnapshot snapshot) {
        return McpTaskMapper.toStatusNotification(
                snapshot, taskResultNode(snapshot), taskErrorNode(snapshot), inputRequestsNode(snapshot));
    }

    @Override
    public Object loggingMessageParams(LoggingLevel level, @Nullable String logger, @Nullable Object data) {
        return new LoggingMessageNotificationParams(
                dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.LoggingLevel.valueOf(level.name()),
                logger,
                JsonUtils.parseJsonNode(JsonRpcCodec.writeValueAsString(data)),
                null);
    }

    @Override
    public Object subscriptionsAcknowledgedParams(RequestId subscriptionId, SubscriptionListenRequest filter) {
        var uris = filter.resourceSubscriptions();
        var taskIds = filter.taskIds();
        return new SubscriptionsAcknowledgedNotificationParams(
                new SubscriptionFilter(
                        trueOrNull(filter.toolsListChanged()),
                        trueOrNull(filter.promptsListChanged()),
                        trueOrNull(filter.resourcesListChanged()),
                        uris.isEmpty() ? null : List.copyOf(uris),
                        taskIds.isEmpty() ? null : List.copyOf(taskIds),
                        null),
                subscriptionIdMeta(subscriptionId));
    }

    @Override
    public Object subscriptionListChangedParams(RequestId subscriptionId) {
        return new NotificationParams(subscriptionIdMeta(subscriptionId));
    }

    @Override
    public Object subscriptionResourceUpdatedParams(RequestId subscriptionId, String uri) {
        return new ResourceUpdatedNotificationParams(uri, subscriptionIdMeta(subscriptionId));
    }

    @Override
    public Object subscriptionsListenGracefulResult(RequestId subscriptionId) {
        return new SubscriptionsListenResult(subscriptionIdMeta(subscriptionId), COMPLETE, null);
    }

    /** Opted-out filter flags are omitted from the wire, not sent as {@code false}. */
    private static @Nullable Boolean trueOrNull(boolean requested) {
        return requested ? Boolean.TRUE : null;
    }

    private static ObjectNode subscriptionIdMeta(RequestId subscriptionId) {
        Object rawId =
                switch (subscriptionId) {
                    case RequestId.StringValue(var v) -> v;
                    case RequestId.NumericValue(var v) -> v;
                };
        return Objects.requireNonNull(JsonUtils.toObjectTree(Map.of(SUBSCRIPTION_ID_META_KEY, rawId)));
    }

    /**
     * Inlines a completed task's outcome (including a tool-level {@code isError: true} failure)
     * into {@code tasks/get}'s {@code result} field, in the same shape a synchronous {@code
     * tools/call} would have returned. {@code null} while the task hasn't reached a
     * result-bearing state, or for a genuine {@link TaskResult.Failed} protocol failure — see
     * {@link #taskErrorNode(TaskSnapshot)}.
     */
    private @Nullable JsonNode taskResultNode(TaskSnapshot snapshot) {
        if (!(snapshot.result() instanceof TaskResult.Completed c)) {
            return null;
        }
        return encodeToTree(CallToolResult.class, (CallToolResult) callToolResult(c.result()));
    }

    /**
     * Inlines a paused task's outstanding {@code inputRequests} into {@code tasks/get} while it's
     * {@code input_required}; {@code null} in every other state.
     */
    private @Nullable JsonNode inputRequestsNode(TaskSnapshot snapshot) {
        var pending = snapshot.pendingInput();
        var encoded = pending != null ? encodedInputRequests(pending.inputRequests()) : null;
        return encoded != null ? encodeToTree(InputRequests.class, encoded) : null;
    }

    /** Inlines a genuine JSON-RPC protocol failure into {@code tasks/get}'s {@code error} field. */
    private @Nullable JsonNode taskErrorNode(TaskSnapshot snapshot) {
        if (!(snapshot.result() instanceof TaskResult.Failed f)) {
            return null;
        }
        var mapped = error(f.error());
        var fields = new LinkedHashMap<String, Object>();
        fields.put("code", mapped.code());
        fields.put("message", mapped.message());
        if (mapped.data() != null) {
            fields.put("data", mapped.data());
        }
        return JsonUtils.toObjectNode(fields);
    }

    private static Tool toTool(ToolDescriptor d) {
        return new Tool(
                d.description(),
                d.inputSchema() != null
                        ? d.inputSchema().json()
                        : JsonSchema.objectSchema().json(),
                d.outputSchema() != null ? d.outputSchema().json() : null,
                toToolAnnotations(d.annotations()),
                JsonUtils.toObjectTree(d.meta()),
                d.name(),
                d.title(),
                toIcons(d.icons()));
    }

    private static Resource toResource(ResourceDescriptor d) {
        return new Resource(
                d.uri(),
                d.description(),
                d.mimeType(),
                toAnnotations(d.annotations()),
                d.size(),
                JsonUtils.toObjectTree(d.meta()),
                d.name(),
                d.title(),
                toIcons(d.icons()));
    }

    private static ResourceTemplate toResourceTemplate(ResourceTemplateDescriptor d) {
        return new ResourceTemplate(
                d.uriTemplate(),
                d.description(),
                d.mimeType(),
                toAnnotations(d.annotations()),
                JsonUtils.toObjectTree(d.meta()),
                d.name(),
                d.title(),
                toIcons(d.icons()));
    }

    private static ResourceContents toResourceContents(dev.tachyonmcp.api.server.domain.ResourceContents domain) {
        return switch (domain) {
            case dev.tachyonmcp.api.server.domain.TextResourceContents t ->
                new TextResourceContents(t.text(), t.uri(), t.mimeType(), JsonUtils.toObjectTree(t.meta()));
            case dev.tachyonmcp.api.server.domain.BlobResourceContents b ->
                new BlobResourceContents(b.blob(), b.uri(), b.mimeType(), JsonUtils.toObjectTree(b.meta()));
        };
    }

    private static Prompt toPrompt(PromptDescriptor d) {
        var arguments = d.arguments().isEmpty()
                ? null
                : d.arguments().stream()
                        .map(a -> new PromptArgument(a.description(), a.required(), a.name(), a.title()))
                        .toList();
        return new Prompt(
                d.description(), arguments, JsonUtils.toObjectTree(d.meta()), d.name(), d.title(), toIcons(d.icons()));
    }

    private static CallToolResult buildCallToolResult(
            List<ContentBlock> content,
            @Nullable Object structuredValue,
            @Nullable Boolean isError,
            @Nullable ObjectNode meta) {
        var blocks = new ArrayList<>(
                content.stream().map(McpResponseMapper::toContentBlock).toList());
        JsonNode structured = null;
        if (structuredValue != null) {
            structured = switch (structuredValue) {
                case JsonDocument document -> JsonUtils.parse(document);
                case JsonNode node -> node;
                default -> JsonUtils.parse(JsonUtils.writeString(structuredValue));
            };
            if (content.stream().noneMatch(TextContent.class::isInstance)) {
                blocks.add(toContentBlock(TextContent.of(structured.toString())));
            }
        }
        return new CallToolResult(blocks, structured, isError, meta, COMPLETE, null);
    }

    private static dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.ContentBlock toContentBlock(
            ContentBlock domain) {
        return switch (domain) {
            case dev.tachyonmcp.api.server.domain.TextContent t ->
                new dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.TextContent(
                        "text", t.text(), toAnnotations(t.annotations()), JsonUtils.toObjectTree(t.meta()));
            case dev.tachyonmcp.api.server.domain.ImageContent i ->
                new dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.ImageContent(
                        "image",
                        i.data(),
                        i.mimeType(),
                        toAnnotations(i.annotations()),
                        JsonUtils.toObjectTree(i.meta()));
            case dev.tachyonmcp.api.server.domain.AudioContent a ->
                new dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.AudioContent(
                        "audio",
                        a.data(),
                        a.mimeType(),
                        toAnnotations(a.annotations()),
                        JsonUtils.toObjectTree(a.meta()));
            case dev.tachyonmcp.api.server.domain.EmbeddedResource e ->
                new dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.EmbeddedResource(
                        "resource",
                        toResourceContents(e.resource()),
                        toAnnotations(e.annotations()),
                        JsonUtils.toObjectTree(e.meta()));
            case dev.tachyonmcp.api.server.domain.ResourceLink r ->
                new dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.ResourceLink(
                        "resource_link",
                        r.name(),
                        r.title(),
                        toIcons(r.icons()),
                        r.uri(),
                        r.description(),
                        r.mimeType(),
                        toAnnotations(r.annotations()),
                        r.size(),
                        JsonUtils.toObjectTree(r.meta()));
        };
    }

    private static dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.PromptMessage toPromptMessage(
            PromptMessage domain) {
        var role =
                switch (domain.role()) {
                    case USER -> dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.Role.USER;
                    case ASSISTANT -> dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.Role.ASSISTANT;
                };
        return new dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.PromptMessage(
                role, toContentBlock(domain.content()));
    }

    private static dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.ToolAnnotations toToolAnnotations(
            @Nullable ToolAnnotations domain) {
        return domain == null
                ? null
                : new dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.ToolAnnotations(
                        domain.title(),
                        domain.readOnlyHint(),
                        domain.destructiveHint(),
                        domain.idempotentHint(),
                        domain.openWorldHint());
    }

    private static dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.Annotations toAnnotations(
            @Nullable Annotations domain) {
        if (domain == null) return null;
        var audience = domain.audience().isEmpty()
                ? null
                : domain.audience().stream()
                        .map(role -> switch (role) {
                            case USER -> dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.Role.USER;
                            case ASSISTANT -> dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.Role.ASSISTANT;
                        })
                        .toList();
        return new dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.Annotations(
                audience, domain.priority(), domain.lastModified());
    }

    private static @Nullable List<dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.Icon> toIcons(
            List<dev.tachyonmcp.api.server.domain.Icon> icons) {
        return icons.isEmpty()
                ? null
                : icons.stream()
                        .map(icon -> new dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.Icon(
                                icon.src(),
                                icon.mimeType(),
                                icon.sizes().isEmpty() ? null : icon.sizes(),
                                icon.theme()))
                        .toList();
    }

    private static <T> JsonNode encodeToTree(Class<T> type, T value) {
        return CodecSupport.encodeToTree(CodecRegistry.codecFor(type), value);
    }
}
