package dev.tachyonmcp.docs.features.completions;

import static dev.tachyonmcp.docs.JsonRpc.items;
import static dev.tachyonmcp.docs.JsonRpc.result;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.server.features.completions.CompletionResult;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.server.annotations.TachyonAnnotationProvider;
import dev.tachyonmcp.testkit.McpTestServers;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class CompletionsDocsTest {

    private static TachyonServer startWith(Object... services) {
        return McpTestServers.start(
                b -> b.annotations(a -> {
                    for (var service : services) {
                        a.register(service);
                    }
                }),
                s -> {});
    }

    private static JsonNode completePrompt(TachyonServer server, String prompt, String argument, String value, String context)
            throws Exception {
        return result(server, "completion/complete", """
                {"ref":{"type":"ref/prompt","name":"%s"},
                 "argument":{"name":"%s","value":"%s"}%s}
                """.formatted(prompt, argument, value, context)).path("completion");
    }

    private static JsonNode completeResource(TachyonServer server, String uri, String argument, String value) throws Exception {
        return result(server, "completion/complete", """
                {"ref":{"type":"ref/resource","uri":"%s"},
                 "argument":{"name":"%s","value":"%s"}}
                """.formatted(uri, argument, value)).path("completion");
    }

    private static List<String> values(JsonNode completion) {
        return items(completion.path("values")).stream().map(JsonNode::asString).toList();
    }

    @Test
    void promptCompletionFiltersByPrefixAndIgnoresOtherArguments() throws Exception {
        try (var server = startWith(new ReviewService())) {
            assertThat(values(completePrompt(server, "review-code", "concern", "se", ""))).containsExactly("security");
            assertThat(values(completePrompt(server, "review-code", "concern", "", "")))
                    .containsExactly("clarity", "performance", "security");
            assertThat(values(completePrompt(server, "review-code", "other", "se", ""))).isEmpty();
        }
    }

    @Test
    void builderRegistersThePromptAndItsCompletion() {
        try (var server = ReviewServer.build()) {
            assertThat(server.prompts().find("review-code")).isPresent();
        }
    }

    @Test
    void resourceVariableCompletionMatchesTheExactUriTemplate() throws Exception {
        try (var server = startWith(new WeatherService())) {
            assertThat(values(completeResource(server, "weather://current/{city}", "city", "p")))
                    .containsExactly("Paris", "Prague");
            assertThat(values(completeResource(server, "weather://current/{town}", "city", "p"))).isEmpty();
        }
    }

    @Test
    void siblingArgumentsNarrowTheCandidatesAndMayBeAbsent() throws Exception {
        try (var server = startWith(new TripService())) {
            assertThat(values(completePrompt(server, "trip", "city", "l", """
                    ,"context":{"arguments":{"country":"France"}}
                    """))).containsExactly("Lyon");
            assertThat(values(completePrompt(server, "trip", "city", "l", ""))).containsExactly("London", "Lyon");
        }
    }

    @Test
    void enumPromptArgumentsCompleteAutomaticallyUnlessTurnedOffPerProvider() throws Exception {
        try (var server = startWith(new EnumPromptService())) {
            assertThat(values(completePrompt(server, "review-by-concern", "concern", "s", "")))
                    .containsExactly("SECURITY");
            assertThat(items(result(server, "prompts/get", """
                            {"name":"review-by-concern","arguments":{"concern":"SECURITY"}}
                            """).path("messages"))
                            .get(0)
                            .path("content")
                            .path("text")
                            .asString())
                    .isEqualTo("Review this code for SECURITY.");
        }
        try (var server = McpTestServers.start(
                b -> b.annotations(a -> a.withProvider(TachyonAnnotationProvider.withEnumCompletions(false))
                        .register(new EnumPromptService())),
                s -> {})) {
            assertThat(values(completePrompt(server, "review-by-concern", "concern", "s", "")))
                    .isEmpty();
        }
    }

    @Test
    void enumCompletionIsAFallbackThatAnExplicitCompletionOverrides() throws Exception {
        class Explicit {
            @dev.tachyonmcp.api.annotations.McpCompletion(prompt = "review-by-concern")
            public List<String> concerns(String concern) {
                return List.of("explicit");
            }
        }
        try (var server = startWith(new EnumPromptService(), new Explicit())) {
            assertThat(values(completePrompt(server, "review-by-concern", "concern", "e", "")))
                    .containsExactly("explicit");
        }
        try (var server = startWith(new Explicit(), new EnumPromptService())) {
            assertThat(values(completePrompt(server, "review-by-concern", "concern", "e", "")))
                    .containsExactly("explicit");
        }
    }

    @Test
    void enumOffBuilderRegistersTheService() {
        try (var server = EnumCompletionsOff.build()) {
            assertThat(server.prompts().find("review-code")).isPresent();
        }
    }

    @Test
    void programmaticPromptCompletionReturnsEmptyForOtherArguments() throws Exception {
        try (var server = McpTestServers.start(b -> {}, ProgrammaticCompletions::registerPrompt)) {
            assertThat(values(completePrompt(server, "review-code", "concern", "p", "")))
                    .containsExactly("performance");
            assertThat(values(completePrompt(server, "review-code", "other", "p", ""))).isEmpty();
            assertThat(values(completePrompt(server, "unregistered", "concern", "p", "")))
                    .as("no handler matches the reference")
                    .isEmpty();
        }
    }

    @Test
    void programmaticResourceCompletionAwaitsTheAsyncLookup() throws Exception {
        try (var server = McpTestServers.start(
                b -> {}, s -> ProgrammaticCompletions.registerResource(s, new CityService()))) {
            assertThat(values(completeResource(server, "weather://current/{city}", "city", "pa")))
                    .containsExactly("Paris");
        }
    }

    @Test
    void wireResponseIsLimitedToOneHundredValuesAndFlagsTruncation() throws Exception {
        var many = IntStream.range(0, 150).mapToObj(i -> "city-" + i).toList();
        try (var server = McpTestServers.start(
                b -> {},
                s -> s.completions().registerForPrompt("big", (context, request) -> CompletionResult.of(many)))) {
            var completion = completePrompt(server, "big", "city", "", "");

            assertThat(values(completion)).hasSize(100);
            assertThat(completion.path("hasMore").asBoolean()).isTrue();
        }
    }
}
