/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.features.prompts;

import dev.tachyonmcp.api.server.ServerFeature;
import dev.tachyonmcp.api.server.domain.Args;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Request parameters for a prompt invocation.
 *
 * @param arguments the prompt arguments, or empty if none were provided
 * @param inputResponses client's input responses for input-required prompts, or null
 * @param requestState opaque state token for input-required prompts, or null
 * @param meta protocol extension metadata, or null
 */
public record PromptRequest(
        Args arguments,
        @Nullable Map<String, Object> inputResponses,
        @Nullable String requestState,
        @Nullable Map<String, Object> meta)
        implements ServerFeature.Request {

    /**
     * Creates a prompt request.
     *
     * @param arguments the arguments
     * @param inputResponses the input responses
     * @param requestState the request state
     * @param meta the metadata entries
     */
    public PromptRequest {
        if (arguments == null) arguments = Args.empty();
    }

    /**
     * Compatibility constructor for callers that predate {@link #meta()}; sets it to {@code null}.
     *
     * @param arguments the prompt arguments, or empty if none were provided
     * @param inputResponses client's input responses for input-required prompts, or null
     * @param requestState opaque state token for input-required prompts, or null
     */
    public PromptRequest(Args arguments, @Nullable Map<String, Object> inputResponses, @Nullable String requestState) {
        this(arguments, inputResponses, requestState, null);
    }
}
