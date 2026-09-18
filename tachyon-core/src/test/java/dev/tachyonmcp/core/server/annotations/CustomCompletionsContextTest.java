/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.annotations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import dev.tachyonmcp.api.annotations.McpCompletion;
import dev.tachyonmcp.api.annotations.McpPrompt;
import dev.tachyonmcp.api.annotations.McpResource;
import dev.tachyonmcp.api.json.PayloadDeserializer;
import dev.tachyonmcp.api.json.PayloadSerializer;
import dev.tachyonmcp.api.server.features.annotations.AnnotationRegistrationContext;
import dev.tachyonmcp.api.server.features.completions.AsyncCompletionFn;
import dev.tachyonmcp.api.server.features.completions.CompletionFn;
import dev.tachyonmcp.api.server.features.completions.Completions;
import dev.tachyonmcp.api.server.features.prompts.Prompts;
import dev.tachyonmcp.api.server.features.resources.Resources;
import dev.tachyonmcp.api.server.features.tools.Tools;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.server.json.JacksonPayloadSerde;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A caller may pass an {@link AnnotationRegistrationContext} whose {@link Completions} is its own
 * implementation rather than the server's registry. Derived enum completion needs if-absent
 * registration, which only that registry offers, so the provider fails fast instead of replacing
 * an explicit handler through ordinary registration. Wire behaviour of the derived handler lives in
 * the e2e {@code EnumCompletionFallbackTest}.
 */
class CustomCompletionsContextTest {

    private static final String TEMPLATE = "weather://seasons/{season}";

    enum Season {
        SUMMER,
        WINTER
    }

    @SuppressWarnings("unused")
    static class SeasonService {

        @McpPrompt
        String packing(Season season) {
            return "Pack for " + season.name();
        }

        @McpResource(uri = TEMPLATE, mimeType = "text/plain")
        String season(Season season) {
            return "Season " + season.name();
        }
    }

    @SuppressWarnings("unused")
    static class CompletedSeasonService {

        @McpPrompt
        String packing(Season season) {
            return "Pack for " + season.name();
        }

        @McpResource(uri = TEMPLATE, mimeType = "text/plain")
        String season(Season season) {
            return "Season " + season.name();
        }

        @McpCompletion(prompt = "packing")
        List<String> packingSeasons(String season) {
            return List.of("prompt:" + season);
        }

        @McpCompletion(resource = TEMPLATE)
        List<String> resourceSeasons(String season) {
            return List.of("resource:" + season);
        }
    }

    /** Records registrations, and supports nothing beyond the façade's own methods. */
    static class RecordingCompletions implements Completions {

        final List<String> calls = new ArrayList<>();

        @Override
        public Completions registerForPrompt(String promptName, CompletionFn fn) {
            calls.add("prompt:" + promptName);
            return this;
        }

        @Override
        public Completions registerForPromptAsync(String promptName, AsyncCompletionFn fn) {
            calls.add("promptAsync:" + promptName);
            return this;
        }

        @Override
        public Completions registerForResource(String uriOrTemplate, CompletionFn fn) {
            calls.add("resource:" + uriOrTemplate);
            return this;
        }

        @Override
        public Completions registerForResourceAsync(String uriOrTemplate, AsyncCompletionFn fn) {
            calls.add("resourceAsync:" + uriOrTemplate);
            return this;
        }

        @Override
        public boolean unregisterForPrompt(String promptName) {
            return false;
        }

        @Override
        public boolean unregisterForResource(String uriOrTemplate) {
            return false;
        }
    }

    private record CustomContext(
            Tools tools,
            Resources resources,
            Prompts prompts,
            Completions completions,
            PayloadSerializer payloadSerializer,
            PayloadDeserializer payloadDeserializer)
            implements AnnotationRegistrationContext {}

    private static void register(TachyonAnnotationProvider provider, Completions completions, Object service) {
        final var serde = new JacksonPayloadSerde();
        try (var server = TachyonServer.builder().build()) {
            provider.register(
                    service,
                    new CustomContext(server.tools(), server.resources(), server.prompts(), completions, serde, serde));
        }
    }

    @Test
    void derivedEnumCompletionFailsFastWhenTheContextCannotRegisterIfAbsent() {
        final var completions = new RecordingCompletions();
        assertThatIllegalStateException()
                .isThrownBy(() -> register(TachyonAnnotationProvider.instance(), completions, new SeasonService()))
                .withMessageContaining("Automatic enum completion for")
                .withMessageContaining("needs the server's completion registry")
                .withMessageContaining(RecordingCompletions.class.getName())
                .withMessageContaining("TachyonAnnotationProvider.withEnumCompletions(false)");
        assertThat(completions.calls).isEmpty();
    }

    @Test
    void disablingEnumCompletionRegistersTheFeaturesAndNoCompletionHandler() {
        final var completions = new RecordingCompletions();
        register(TachyonAnnotationProvider.withEnumCompletions(false), completions, new SeasonService());
        assertThat(completions.calls).isEmpty();
    }

    @Test
    void anExplicitCompletionForTheEnumTargetNeedsNoDerivedHandler() {
        final var completions = new RecordingCompletions();
        register(TachyonAnnotationProvider.instance(), completions, new CompletedSeasonService());
        assertThat(completions.calls).containsExactlyInAnyOrder("prompt:packing", "resource:" + TEMPLATE);
    }

    @Test
    void withEnumCompletionsTrueIsTheSharedInstance() {
        assertThat(TachyonAnnotationProvider.withEnumCompletions(true)).isSameAs(TachyonAnnotationProvider.instance());
        assertThat(TachyonAnnotationProvider.withEnumCompletions(false))
                .isNotSameAs(TachyonAnnotationProvider.instance())
                .isSameAs(TachyonAnnotationProvider.withEnumCompletions(false));
    }
}
