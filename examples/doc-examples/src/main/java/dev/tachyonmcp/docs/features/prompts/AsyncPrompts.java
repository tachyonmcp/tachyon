package dev.tachyonmcp.docs.features.prompts;

import dev.tachyonmcp.api.server.domain.PromptMessage;
import dev.tachyonmcp.api.server.features.prompts.PromptDescriptor;
import dev.tachyonmcp.api.server.features.prompts.PromptResult;
import dev.tachyonmcp.core.server.TachyonServer;
import java.util.List;

final class AsyncPrompts {

    private AsyncPrompts() {}

    static void register(TachyonServer server, PromptDescriptor descriptor, PromptService promptService) {
        // snips-start: prompts_async
        server.prompts().registerAsync(
                descriptor,
                (context, request) -> promptService.create(request.arguments())
                        .thenApply(message -> PromptResult.messages(List.of(PromptMessage.user(message)))));
        // snips-end: prompts_async
    }
}
