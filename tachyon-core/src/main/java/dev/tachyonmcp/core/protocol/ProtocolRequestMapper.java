/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.protocol;

import dev.tachyonmcp.api.json.JsonObject;
import dev.tachyonmcp.api.json.PayloadDeserializer;
import dev.tachyonmcp.api.server.domain.LoggingLevel;
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
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Maps protocol-specific request parameters to protocol-neutral domain requests.
 *
 * <p>Each protocol version (e.g. MCP 2025-11-25) provides its own implementation, mirroring
 * {@link ProtocolResponseMapper} on the response side.
 */
public interface ProtocolRequestMapper {

    /**
     * Maps pagination params ({@code cursor}, and an optional {@code limit}) into a {@link PageRequest}.
     */
    PageRequest page(@Nullable Object params);

    /**
     * Maps {@code tools/call} params, deserializing arguments with the given {@link PayloadDeserializer}.
     */
    ToolCallRequest callTool(@Nullable Object params, PayloadDeserializer payloadDeserializer);

    /**
     * Whether this protocol supports MCP 2025-11-25's legacy {@code tools/call.task} augmentation.
     *
     * <p>MCP 2026-07-28 ignores that field; task creation is server-directed through the tasks
     * extension instead.
     */
    default boolean supportsLegacyTaskAugmentation() {
        return true;
    }

    /**
     * Maps {@code prompts/get} params into a prompt name and its argument request.
     */
    PromptCallRequest getPrompt(@Nullable Object params);

    /** Maps {@code resources/read} params into a resource request. */
    ResourceRequest readResource(@Nullable Object params);

    /** Maps {@code completion/complete} params into a completion reference and its request. */
    CompletionCallRequest complete(@Nullable Object params);

    /** Extracts the resource {@code uri} from params. */
    String resourceUri(@Nullable Object params);

    /** Maps {@code tasks/get} params into a task lookup request. */
    TaskGetRequest taskGet(@Nullable Object params);

    /** Maps {@code tasks/cancel} params into a cancellation request. */
    TaskCancelRequest taskCancel(@Nullable Object params);

    /** Maps legacy {@code tasks/result} params into a blocking-result request. */
    TaskAwaitResultRequest taskAwaitResult(@Nullable Object params);

    /** Maps {@code tasks/update} params into the target task id and its input responses. */
    TaskUpdateRequest taskUpdate(@Nullable Object params);

    /** Extracts the requested {@link LoggingLevel} from {@code logging/setLevel} params. */
    LoggingLevel loggingLevel(@Nullable Object params);

    /** Maps {@code initialize} params into the client's requested extensions. */
    InitializeRequest initialize(@Nullable Object params);

    /**
     * Extracts the extensions the client declares support for on this request (ID to client settings),
     * from {@code _meta."io.modelcontextprotocol/clientCapabilities".extensions} (2026-07-28, SEP-2575) —
     * same shape as {@link InitializeRequest#extensions()}, so both feed the same negotiator. Protocols
     * negotiated once via {@code initialize} (e.g. 2025-11-25) carry no such per-request declaration and
     * return an empty map.
     *
     * @throws RequestMappingException with {@code invalid_params} if params are not an object
     */
    Map<String, JsonObject> declaredExtensions(@Nullable Object params);

    /**
     * Extracts the client's permitted {@link LoggingLevel}, or {@code null} if unset.
     *
     * @throws RequestMappingException with {@code invalid_params} if params or {@code _meta} are not objects
     */
    @Nullable
    LoggingLevel permittedLogLevel(@Nullable Object params);

    /** Maps {@code notifications/cancelled} params, or {@code null} if the request cannot be identified. */
    @Nullable
    CancellationRequest cancellation(@Nullable Object params);

    /** Maps {@code tasks/status} notification params, or {@code null} if unparseable. */
    @Nullable
    TaskStatusRequest taskStatus(@Nullable Object params);

    /**
     * Whether this protocol supports {@code subscriptions/listen} (2026-07-28's replacement for
     * {@code resources/subscribe} and the plain HTTP GET stream).
     */
    default boolean supportsSubscriptionsListen() {
        return false;
    }

    /** Maps {@code subscriptions/listen} params into the requested notification filter. */
    default SubscriptionListenRequest subscriptionsListen(@Nullable Object params) {
        throw new UnsupportedOperationException("subscriptions/listen is not supported by this protocol version");
    }

    /**
     * A page request.
     *
     * @param limit  maximum number of items to return
     * @param cursor opaque continuation token from a prior page, or {@code null} for the first page
     * @param meta request metadata, or {@code null} when absent
     */
    record PageRequest(
            int limit, @Nullable String cursor, @Nullable Map<String, Object> meta) {}

    /**
     * A tool call request.
     *
     * @param request the tool name and deserialized arguments
     * @param taskAugmented whether the client requested task-augmented (async) execution
     * @param taskTtl requested task retention duration, or {@code null} to use the server default
     */
    record ToolCallRequest(
            ToolRequest request,
            boolean taskAugmented,
            @Nullable Duration taskTtl) {}

    /**
     * A prompt call request.
     *
     * @param name    the prompt's registered name
     * @param request the prompt's argument request
     */
    record PromptCallRequest(String name, PromptRequest request) {}

    /**
     * A completion request.
     *
     * @param reference the prompt or resource being completed
     * @param request   the completion argument request
     */
    record CompletionCallRequest(CompletionReference reference, CompletionRequest request) {}

    /** Identifies what a completion request is completing against. */
    sealed interface CompletionReference {

        /**
         * Completion against a prompt's argument.
         *
         * @param name the prompt's registered name
         */
        record Prompt(String name) implements CompletionReference {}

        /**
         * Completion against a resource template's URI variable.
         *
         * @param uri the resource template URI
         */
        record Resource(String uri) implements CompletionReference {}
    }

    /**
     * An initialize request.
     *
     * @param extensions client-requested protocol extensions, keyed by extension name
     */
    record InitializeRequest(Map<String, JsonObject> extensions) {}

    /**
     * A cancellation notification.
     *
     * @param requestId the ID of the request being cancelled
     * @param reason optional human-readable cancellation reason, or {@code null}
     */
    record CancellationRequest(
            RequestId requestId, @Nullable String reason) {}

    /**
     * A task status update.
     *
     * @param taskId the task's ID
     * @param state the task's new state
     * @param message optional human-readable status message, or {@code null}
     */
    record TaskStatusRequest(
            String taskId, TaskState state, @Nullable String message) {}

    /**
     * The notification filter requested on a {@code subscriptions/listen} call.
     *
     * @param toolsListChanged whether to receive {@code notifications/tools/list_changed}
     * @param promptsListChanged whether to receive {@code notifications/prompts/list_changed}
     * @param resourcesListChanged whether to receive {@code notifications/resources/list_changed}
     * @param resourceSubscriptions resource URIs to receive {@code notifications/resources/updated} for
     * @param taskIds task IDs to receive {@code notifications/tasks} for (tasks extension, SEP-2663;
     *     not part of the core 2026-07-28 schema)
     */
    record SubscriptionListenRequest(
            boolean toolsListChanged,
            boolean promptsListChanged,
            boolean resourcesListChanged,
            Set<String> resourceSubscriptions,
            Set<String> taskIds) {

        /** Returns this filter with {@code taskIds} replaced, e.g. by the subset a listener may read. */
        public SubscriptionListenRequest withTaskIds(Set<String> taskIds) {
            return new SubscriptionListenRequest(
                    toolsListChanged, promptsListChanged, resourcesListChanged, resourceSubscriptions, taskIds);
        }
    }
}
