package dev.tachyonmcp.docs.features.prompts;

import static dev.tachyonmcp.docs.ForkedMain.DOCUMENTED_PORT;
import static dev.tachyonmcp.docs.JsonRpc.items;
import static dev.tachyonmcp.docs.JsonRpc.named;
import static dev.tachyonmcp.docs.JsonRpc.result;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.server.features.prompts.PromptDescriptor;
import dev.tachyonmcp.docs.ForkedMain;
import dev.tachyonmcp.testkit.McpTestServers;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class PromptsDocsTest {

    private static List<String> arguments(JsonNode prompt) {
        return items(prompt.path("arguments")).stream()
                .map(a -> a.path("name").asString() + ":" + a.path("required").asBoolean())
                .toList();
    }

    private static JsonNode messages(JsonNode promptsGetResult) {
        return promptsGetResult.path("messages");
    }

    @Test
    void annotatedMainAdvertisesRequiredAndOptionalArgumentsAndRendersTheMessage() throws Exception {
        try (var ignored = ForkedMain.start("dev.tachyonmcp.docs.features.prompts.AnnotatedPromptsServer")) {
            var prompt = named(result(DOCUMENTED_PORT, "prompts/list"), "prompts", "review-code");

            assertThat(prompt.path("description").asString()).isEqualTo("Review code for a selected concern");
            assertThat(arguments(prompt)).containsExactlyInAnyOrder("concern:true", "language:false");

            var withoutLanguage = messages(result(DOCUMENTED_PORT, "prompts/get", """
                    {"name":"review-code","arguments":{"concern":"security"}}
                    """)).get(0);
            assertThat(withoutLanguage.path("role").asString()).isEqualTo("user");
            assertThat(withoutLanguage.path("content").path("text").asString())
                    .isEqualTo("Review this code for security.");

            var withLanguage = messages(result(DOCUMENTED_PORT, "prompts/get", """
                    {"name":"review-code","arguments":{"concern":"security","language":"Java"}}
                    """)).get(0);
            assertThat(withLanguage.path("content").path("text").asString())
                    .isEqualTo("Review this code for security in Java.");
        }
    }

    @Test
    void explicitMessagesKeepTheirOwnRoles() throws Exception {
        try (var server = McpTestServers.start(
                b -> b.annotations(a -> a.register(new ConversationPrompts())), s -> {})) {
            var conversation = messages(result(server, "prompts/get", """
                    {"name":"review-conversation","arguments":{"concern":"clarity"}}
                    """));

            assertThat(items(conversation))
                    .extracting(m -> m.path("role").asString() + ":" + m.path("content").path("text").asString())
                    .containsExactly(
                            "user:Review this code for clarity.", "assistant:Share the code you want reviewed.");
        }
    }

    @Test
    void programmaticMainServesTheDescriptorArgumentsAndHandler() throws Exception {
        try (var ignored = ForkedMain.start("dev.tachyonmcp.docs.features.prompts.ProgrammaticPromptsServer")) {
            var prompt = named(result(DOCUMENTED_PORT, "prompts/list"), "prompts", "review-code");
            var concern = prompt.path("arguments").get(0);

            assertThat(concern.path("name").asString()).isEqualTo("concern");
            assertThat(concern.path("title").asString()).isEqualTo("Concern");
            assertThat(concern.path("description").asString()).isEqualTo("Security, performance, or clarity");
            assertThat(concern.path("required").asBoolean()).isTrue();

            var message = messages(result(DOCUMENTED_PORT, "prompts/get", """
                    {"name":"review-code","arguments":{"concern":"performance"}}
                    """)).get(0);
            assertThat(message.path("content").path("text").asString()).isEqualTo("Review this code for performance.");
        }
    }

    @Test
    void asyncHandlerResultIsAwaited() throws Exception {
        try (var server = McpTestServers.start(
                b -> {},
                s -> AsyncPrompts.register(
                        s, PromptDescriptor.builder().name("async-review").build(), new PromptService()))) {
            var message = messages(result(server, "prompts/get", """
                    {"name":"async-review","arguments":{"concern":"security"}}
                    """)).get(0);

            assertThat(message.path("content").path("text").asString()).isEqualTo("Review this code for security.");
        }
    }
}
