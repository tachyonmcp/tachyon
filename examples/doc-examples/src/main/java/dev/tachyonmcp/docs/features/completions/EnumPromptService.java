package dev.tachyonmcp.docs.features.completions;

import dev.tachyonmcp.api.annotations.McpPrompt;

final class EnumPromptService {

    // snips-start: completions_enum
    public enum Concern { CLARITY, PERFORMANCE, SECURITY }

    @McpPrompt(name = "review-by-concern")
    public String reviewByConcern(Concern concern) {
        return "Review this code for " + concern + ".";
    }
    // snips-end: completions_enum
}
