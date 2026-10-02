package dev.tachyonmcp.docs.annotations;

import dev.tachyonmcp.api.annotations.McpCompletion;
import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.features.completions.CompletionRequest;
import dev.tachyonmcp.api.server.features.completions.CompletionResult;

final class RequestCompletions {
    private final Suggestions suggestions = new Suggestions();

    // snips-start: annotations_completion_request
    @McpCompletion(prompt = "trip")
    CompletionResult completeTrip(CompletionRequest request, InteractionContext context) {
        return suggestions.complete(request.argumentName(), request.argumentValue(), request.resolvedArguments());
    }
    // snips-end: annotations_completion_request
}
