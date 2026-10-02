package dev.tachyonmcp.docs.features.prompts;

import dev.tachyonmcp.api.server.domain.PromptArgument;
import dev.tachyonmcp.api.server.domain.PromptMessage;
import dev.tachyonmcp.api.server.features.prompts.PromptResult;
import dev.tachyonmcp.core.server.TachyonServer;
import java.util.List;

public final class ProgrammaticPromptsServer {

    private ProgrammaticPromptsServer() {}

    public static void main(String[] args) {
        // snips-start: prompts_programmatic_server
        var server = TachyonServer.builder()
                .withPrompts(prompts -> prompts.register(
                        prompt -> prompt
                                .name("review-code")
                                .description("Review code for a selected concern")
                                .addArguments(PromptArgument.of(
                                        "concern", "Concern", "Security, performance, or clarity", true)),
                        (context, request) -> {
                            var concern = request.arguments().stringValue("concern");
                            return PromptResult.messages(List.of(PromptMessage.user(
                                    "Review this code for " + concern + ".")));
                        }))
                .port(8080)
                .build();
        // snips-end: prompts_programmatic_server
        server.start();
    }
}
