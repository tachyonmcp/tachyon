package dev.tachyonmcp.docs.features.completions;

import dev.tachyonmcp.api.server.features.completions.CompletionResult;
import dev.tachyonmcp.core.server.TachyonServer;
import java.util.List;
import java.util.Locale;

final class ProgrammaticCompletions {

    private ProgrammaticCompletions() {}

    static void registerPrompt(TachyonServer server) {
        // snips-start: completions_programmatic_prompt
        server.completions().registerForPrompt("review-code", (context, request) -> {
            if (!request.argumentName().equals("concern")) {
                return CompletionResult.empty();
            }

            var prefix = request.argumentValue().toLowerCase(Locale.ROOT);
            var matches = List.of("clarity", "performance", "security").stream()
                    .filter(value -> value.startsWith(prefix))
                    .toList();
            return CompletionResult.of(matches);
        });
        // snips-end: completions_programmatic_prompt
    }

    static void registerResource(TachyonServer server, CityService cityService) {
        // snips-start: completions_programmatic_resource
        server.completions().registerForResourceAsync(
                "weather://current/{city}",
                (context, request) -> cityService.search(request.argumentValue())
                        .thenApply(CompletionResult::of));
        // snips-end: completions_programmatic_resource
    }
}
