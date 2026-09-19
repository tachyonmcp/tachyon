/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.protocol;

import dev.tachyonmcp.api.json.JsonObject;
import dev.tachyonmcp.api.server.config.ServerIdentity;
import dev.tachyonmcp.api.server.domain.InputRequest;
import dev.tachyonmcp.api.server.domain.LoggingLevel;
import dev.tachyonmcp.api.server.domain.ProgressToken;
import dev.tachyonmcp.api.server.domain.PromptMessage;
import dev.tachyonmcp.api.server.domain.RequestId;
import dev.tachyonmcp.api.server.domain.ResourceContents;
import dev.tachyonmcp.api.server.domain.ServerCapabilities;
import dev.tachyonmcp.api.server.domain.ServerError;
import dev.tachyonmcp.api.server.domain.TaskResult;
import dev.tachyonmcp.api.server.features.completions.CompletionResult;
import dev.tachyonmcp.api.server.features.prompts.PromptDescriptor;
import dev.tachyonmcp.api.server.features.resources.ResourceDescriptor;
import dev.tachyonmcp.api.server.features.resources.ResourceTemplateDescriptor;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.api.server.features.tools.ToolDescriptor;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.protocol.ProtocolRequestMapper.SubscriptionListenRequest;
import dev.tachyonmcp.core.server.domain.InitializeResponse;
import dev.tachyonmcp.core.server.json.JsonUtils;
import dev.tachyonmcp.core.transport.jsonrpc.JsonRpcCodec;
import dev.tachyonmcp.core.transport.jsonrpc.JsonRpcError;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Maps domain objects to protocol-specific response shapes.
 *
 * <p>Each protocol version (e.g. MCP 2025-11-25) provides its own implementation
 * registered via {@link java.util.ServiceLoader}.
 */
public interface ProtocolResponseMapper {

    /** Returns {@code true} when this mapper handles the given protocol family and version. */
    boolean supports(String protocolName, String protocolVersion);

    /**
     * Serializes a value this mapper produced into its JSON wire form. Values built from a protocol
     * version's generated models are only encoded correctly by that version's codecs, so callers
     * hand the value back to the mapper that produced it instead of serializing it themselves. The
     * default handles JSON trees, maps, lists and scalars.
     *
     * @param value the mapped response, notification params or result
     * @return the value's JSON representation
     */
    default String encode(Object value) {
        return JsonRpcCodec.toJsonParams(value);
    }

    /** Returns the protocol-specific empty result sent for methods that return no data. */
    Object emptyResult();

    /** Maps a protocol-neutral server error to this protocol version's JSON-RPC error payload. */
    JsonRpcError error(ServerError error);

    /** Maps the server discovery response into a protocol-specific shape. */
    default Object discoverResult(
            List<String> supportedVersions,
            ServerCapabilities capabilities,
            ServerIdentity serverIdentity,
            Map<String, JsonObject> registeredExtensions) {
        throw new UnsupportedOperationException("server/discover is not supported by this protocol version");
    }

    /** Maps a completion result into a protocol-specific shape. */
    Object completeResult(CompletionResult result);

    /** Maps the server's initialize response into protocol-specific shape. */
    Object initializeResult(InitializeResponse response);

    /** Maps a paginated list of tool descriptors into protocol-specific shape. */
    Object listToolsResult(List<ToolDescriptor> tools, @Nullable String nextCursor);

    /** Maps a tool call result into protocol-specific shape. */
    Object callToolResult(ToolResult result);

    /** Maps a paginated list of resource descriptors into protocol-specific shape. */
    Object listResourcesResult(List<ResourceDescriptor> resources, @Nullable String nextCursor);

    /** Maps a paginated list of resource template entries into protocol-specific shape. */
    Object listResourceTemplatesResult(List<ResourceTemplateDescriptor> templates, @Nullable String nextCursor);

    /** Maps a resource contents list (from a read operation) into protocol-specific shape. */
    Object readResourceResult(List<ResourceContents> contents);

    /**
     * Maps resource contents with an optional private-cache requirement. Protocols without cache
     * scope hints use the ordinary resource-result shape.
     */
    default Object readResourceResult(List<ResourceContents> contents, boolean privateCaching) {
        return readResourceResult(contents);
    }

    /** Maps a paginated list of prompt descriptors into protocol-specific shape. */
    Object listPromptsResult(List<PromptDescriptor> prompts, @Nullable String nextCursor);

    /** Maps prompt messages, metadata, and optional description into protocol-specific shape. */
    Object getPromptResult(
            @Nullable String description, List<PromptMessage> messages, @Nullable Map<String, Object> meta);

    /** Maps input-required metadata into protocol-specific shape. */
    Object inputRequiredResult(
            Map<String, ? extends InputRequest> inputRequests,
            @Nullable String requestState,
            @Nullable Map<String, Object> meta);

    /** Maps a paginated list of task snapshots into protocol-specific shape. */
    Object listTasksResult(List<TaskSnapshot> snapshots, @Nullable String nextCursor);

    /** Maps a single task snapshot into protocol-specific shape. */
    Object getTaskResult(TaskSnapshot snapshot);

    /** Maps an initial task snapshot into a CreateTaskResult. */
    Object createTaskResult(TaskSnapshot snapshot);

    /** Maps the task-bearing legacy cancellation response. Modern cancellation uses {@link #emptyResult()}. */
    Object cancelTaskResult(TaskSnapshot snapshot);

    /** Maps a task's terminal result into the tasks/result payload — a {@code CallToolResult}. */
    Object getTaskPayloadResult(@Nullable TaskResult result, String taskId);

    /** Builds the params object for a tasks/status notification from a task snapshot. */
    Object taskStatusNotificationParams(TaskSnapshot snapshot);

    /** Builds the params for a {@code notifications/message} notification. */
    Object loggingMessageParams(LoggingLevel level, @Nullable String logger, @Nullable Object data);

    /**
     * Builds the params for a {@code notifications/progress} notification. The same wire shape is
     * shared by every protocol version, so this has one implementation for all of them, built with
     * {@link JsonUtils#toObjectNode} rather than a generated model: the generated
     * {@code ProgressNotificationParams.progressToken} field is typed {@code String}-only in every
     * version's codegen output, narrower than the spec's {@code string | number} — converting a
     * numeric token through it would silently turn a JSON number into a JSON string on the wire.
     */
    default Object progressNotificationParams(
            ProgressToken token, double progress, @Nullable Double total, @Nullable String message) {
        Object wireToken =
                switch (token) {
                    case ProgressToken.StringValue(var v) -> v;
                    case ProgressToken.NumericValue(var v) -> v;
                };
        var fields = new LinkedHashMap<String, Object>(4);
        fields.put("progressToken", wireToken);
        fields.put("progress", progress);
        // total and message are optional per spec -- omit rather than emitting a misleading total:0.
        if (total != null) {
            fields.put("total", total);
        }
        if (message != null) {
            fields.put("message", message);
        }
        return JsonUtils.toObjectNode(fields);
    }

    /**
     * Builds the ack-first {@code notifications/subscriptions/acknowledged} params sent when a new
     * {@code subscriptions/listen} stream is opened.
     */
    default Object subscriptionsAcknowledgedParams(RequestId subscriptionId, SubscriptionListenRequest filter) {
        throw new UnsupportedOperationException("subscriptions/listen is not supported by this protocol version");
    }

    /**
     * Builds the params for a {@code notifications/tools|prompts|resources/list_changed} notification
     * pushed on a {@code subscriptions/listen} stream.
     */
    default Object subscriptionListChangedParams(RequestId subscriptionId) {
        throw new UnsupportedOperationException("subscriptions/listen is not supported by this protocol version");
    }

    /**
     * Builds the params for a {@code notifications/resources/updated} notification pushed on a
     * {@code subscriptions/listen} stream.
     */
    default Object subscriptionResourceUpdatedParams(RequestId subscriptionId, String uri) {
        throw new UnsupportedOperationException("subscriptions/listen is not supported by this protocol version");
    }

    /**
     * Builds the graceful-closure result sent when the server tears down a {@code
     * subscriptions/listen} stream on its own initiative (e.g. shutdown).
     */
    default Object subscriptionsListenGracefulResult(RequestId subscriptionId) {
        throw new UnsupportedOperationException("subscriptions/listen is not supported by this protocol version");
    }
}
