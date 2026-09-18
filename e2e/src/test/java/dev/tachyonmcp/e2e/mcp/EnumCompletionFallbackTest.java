/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp;

import static dev.tachyonmcp.testkit.JsonRpcResponseAssert.assertThat;

import dev.tachyonmcp.api.annotations.McpCompletion;
import dev.tachyonmcp.api.annotations.McpPrompt;
import dev.tachyonmcp.api.annotations.McpResource;
import dev.tachyonmcp.core.server.AnnotationContext;
import dev.tachyonmcp.core.server.annotations.TachyonAnnotationProvider;
import dev.tachyonmcp.testkit.McpTestClients;
import dev.tachyonmcp.testkit.McpTestServers;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Enum-derived completion is a fallback: an explicit {@code @McpCompletion} wins, whichever
 * service declares it and whichever order the services are registered in. It is also optional —
 * {@code TachyonAnnotationProvider.withEnumCompletions(false)} derives nothing.
 */
class EnumCompletionFallbackTest {

    enum Season {
        SUMMER,
        WINTER
    }

    @SuppressWarnings("unused")
    static class CompletionService {

        @McpCompletion(prompt = "packing")
        List<String> packing(String season) {
            return List.of("prompt:" + season);
        }

        @McpCompletion(resource = "weather://seasons/{season}")
        List<String> seasons(String season) {
            return List.of("resource:" + season);
        }
    }

    @SuppressWarnings("unused")
    static class SeasonService {

        @McpPrompt
        String packing(Season season) {
            return "Pack for " + season.name();
        }

        @McpResource(uri = "weather://seasons/{season}", mimeType = "text/plain")
        String season(Season season) {
            return "Season " + season.name();
        }
    }

    @Test
    void explicitCompletionRegisteredBeforeTheEnumOwnerSurvives() throws Exception {
        assertExplicitCompletionWins(
                annotations -> annotations.register(new CompletionService()).register(new SeasonService()));
    }

    @Test
    void explicitCompletionRegisteredAfterTheEnumOwnerReplacesTheFallback() throws Exception {
        assertExplicitCompletionWins(
                annotations -> annotations.register(new SeasonService()).register(new CompletionService()));
    }

    @Test
    void enumFallbackAnswersWhenNoServiceDeclaresACompletion() throws Exception {
        try (var server = McpTestServers.start(
                        builder -> builder.annotations(annotations -> annotations.register(new SeasonService())),
                        ignored -> {});
                var client = McpTestClients.latest(server.port())) {
            // language=json
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":3,"method":"completion/complete","params":{
                     "ref":{"type":"ref/prompt","name":"packing"},"argument":{"name":"season","value":"w"}}}
                    """)).isSuccess().hasId(3).hasResult("""
                    {"completion":{"values":["WINTER"]},"resultType":"complete"}
                    """);
            // language=json
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":4,"method":"completion/complete","params":{
                     "ref":{"type":"ref/resource","uri":"weather://seasons/{season}"},
                     "argument":{"name":"season","value":"su"}}}
                    """)).isSuccess().hasId(4).hasResult("""
                    {"completion":{"values":["SUMMER"]},"resultType":"complete"}
                    """);
        }
    }

    @Test
    void enumFallbackIsAbsentWhenTheProviderHasEnumCompletionsOff() throws Exception {
        try (var server = McpTestServers.start(
                        builder -> builder.annotations(annotations -> annotations
                                .withProvider(TachyonAnnotationProvider.withEnumCompletions(false))
                                .register(new SeasonService())),
                        ignored -> {});
                var client = McpTestClients.latest(server.port())) {
            // language=json
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":5,"method":"completion/complete","params":{
                     "ref":{"type":"ref/prompt","name":"packing"},"argument":{"name":"season","value":"w"}}}
                    """)).isSuccess().hasId(5).hasResult("""
                    {"completion":{"values":[],"hasMore":false},"resultType":"complete"}
                    """);
            // language=json
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":6,"method":"completion/complete","params":{
                     "ref":{"type":"ref/resource","uri":"weather://seasons/{season}"},
                     "argument":{"name":"season","value":"su"}}}
                    """)).isSuccess().hasId(6).hasResult("""
                    {"completion":{"values":[],"hasMore":false},"resultType":"complete"}
                    """);
        }
    }

    private static void assertExplicitCompletionWins(Consumer<AnnotationContext> services) throws Exception {
        try (var server = McpTestServers.start(builder -> builder.annotations(services), ignored -> {});
                var client = McpTestClients.latest(server.port())) {
            // language=json
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"completion/complete","params":{
                     "ref":{"type":"ref/prompt","name":"packing"},"argument":{"name":"season","value":"w"}}}
                    """)).isSuccess().hasId(1).hasResult("""
                    {"completion":{"values":["prompt:w"]},"resultType":"complete"}
                    """);
            // language=json
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":2,"method":"completion/complete","params":{
                     "ref":{"type":"ref/resource","uri":"weather://seasons/{season}"},
                     "argument":{"name":"season","value":"su"}}}
                    """)).isSuccess().hasId(2).hasResult("""
                    {"completion":{"values":["resource:su"]},"resultType":"complete"}
                    """);
        }
    }
}
