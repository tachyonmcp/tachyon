package dev.tachyonmcp.docs.features.prompts

import dev.tachyonmcp.api.server.domain.PromptArgument
import dev.tachyonmcp.api.server.domain.PromptMessage
import dev.tachyonmcp.kotlin.server.config.TachyonServerBuilder

internal fun TachyonServerBuilder.reviewPrompt() {
    // snips-start: prompts_kotlin
    prompt(
        name = "review-code",
        description = "Review code for a selected concern",
        arguments = listOf(
            PromptArgument.of("concern", "Concern", "Security, performance, or clarity", true),
        ),
    ) {
        listOf(PromptMessage.user("Review this code for ${arguments.stringValue("concern")}."))
    }
    // snips-end: prompts_kotlin
}
