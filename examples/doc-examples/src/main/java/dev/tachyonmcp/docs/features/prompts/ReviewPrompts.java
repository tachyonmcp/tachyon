package dev.tachyonmcp.docs.features.prompts;

// snips-start: prompts_review_prompts
import dev.tachyonmcp.api.annotations.McpPrompt;
import org.jspecify.annotations.Nullable;

class ReviewPrompts {
    @McpPrompt(name = "review-code", description = "Review code for a selected concern")
    public String review(String concern, @Nullable String language) {
        return "Review this code for " + concern
                + (language == null ? "" : " in " + language) + ".";
    }
}
// snips-end: prompts_review_prompts
