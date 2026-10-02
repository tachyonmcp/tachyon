package dev.tachyonmcp.docs.features.prompts;

import dev.tachyonmcp.api.annotations.McpPrompt;
import dev.tachyonmcp.api.server.domain.PromptMessage;
import dev.tachyonmcp.api.server.domain.Role;
import dev.tachyonmcp.api.server.domain.TextContent;
import java.util.List;

final class ConversationPrompts {

    // snips-start: prompts_conversation
    @McpPrompt(name = "review-conversation")
    public List<PromptMessage> conversation(String concern) {
        return List.of(
                PromptMessage.user("Review this code for " + concern + "."),
                PromptMessage.of(Role.ASSISTANT, TextContent.of("Share the code you want reviewed.")));
    }
    // snips-end: prompts_conversation
}
