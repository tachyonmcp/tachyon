package dev.tachyonmcp.docs.features.completions;

// snips-start: completions_review_service
import dev.tachyonmcp.api.annotations.McpCompletion;
import dev.tachyonmcp.api.annotations.McpPrompt;
import java.util.List;
import java.util.Locale;

class ReviewService {
    @McpPrompt(name = "review-code")
    public String review(String concern) {
        return "Review this code for " + concern + ".";
    }

    @McpCompletion(prompt = "review-code")
    public List<String> concerns(String concern) {
        var prefix = concern.toLowerCase(Locale.ROOT);
        return List.of("clarity", "performance", "security").stream()
                .filter(value -> value.startsWith(prefix))
                .toList();
    }
}
// snips-end: completions_review_service
