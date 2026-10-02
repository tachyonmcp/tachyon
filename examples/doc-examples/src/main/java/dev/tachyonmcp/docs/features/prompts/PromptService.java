package dev.tachyonmcp.docs.features.prompts;

import dev.tachyonmcp.api.server.domain.Args;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

final class PromptService {

    CompletionStage<String> create(Args arguments) {
        var concern = arguments.stringValue("concern");
        return CompletableFuture.supplyAsync(() -> "Review this code for " + concern + ".");
    }
}
