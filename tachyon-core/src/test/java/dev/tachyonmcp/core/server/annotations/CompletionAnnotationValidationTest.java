/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.annotations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import dev.tachyonmcp.api.annotations.McpCompletion;
import dev.tachyonmcp.api.annotations.McpPrompt;
import dev.tachyonmcp.api.annotations.McpResource;
import dev.tachyonmcp.api.annotations.McpTool;
import dev.tachyonmcp.api.server.features.completions.CompletionRequest;
import dev.tachyonmcp.core.server.TachyonServer;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CompletionAnnotationValidationTest {
    static class MissingTarget {
        @McpCompletion
        List<String> complete(String city) {
            return List.of();
        }
    }

    static class BothTargets {
        @McpCompletion(prompt = "trip", resource = "city://{city}")
        List<String> complete(String city) {
            return List.of();
        }
    }

    static class NoArgument {
        @McpCompletion(prompt = "trip")
        List<String> complete() {
            return List.of();
        }
    }

    static class NumericPartial {
        @McpCompletion(prompt = "trip")
        List<String> complete(int city) {
            return List.of();
        }
    }

    static class MixedRequest {
        @McpCompletion(prompt = "trip")
        List<String> complete(String city, CompletionRequest request) {
            return List.of();
        }
    }

    static class BadReturn {
        @McpCompletion(prompt = "trip")
        String complete(String city) {
            return city;
        }
    }

    static class NonStringCandidates {
        @McpCompletion(prompt = "trip")
        List<Integer> complete(String city) {
            return List.of(1);
        }
    }

    static class DuplicateTarget {
        @McpCompletion(prompt = "trip")
        List<String> cities(String city) {
            return List.of();
        }

        @McpCompletion(prompt = "trip")
        List<String> countries(String country) {
            return List.of();
        }
    }

    static class MixedFeatures {
        @McpTool
        @McpCompletion(prompt = "trip")
        List<String> complete(String city) {
            return List.of();
        }
    }

    static class UnknownPromptArgument {
        @McpCompletion(prompt = "trip")
        List<String> complete(String town) {
            return List.of();
        }

        @McpPrompt
        String trip(String city) {
            return city;
        }
    }

    static class UnknownTemplateVariable {
        @McpCompletion(resource = "city://{city}")
        List<String> complete(String town) {
            return List.of();
        }

        @McpResource(uri = "city://{city}")
        String city(String city) {
            return city;
        }
    }

    static class TripPrompt {
        @McpPrompt
        String trip(String city) {
            return city;
        }
    }

    static Stream<Arguments> invalidDeclarations() {
        return Stream.of(
                Arguments.of(
                        new UnknownPromptArgument(),
                        "@McpCompletion argument 'town' is not declared by prompt 'trip' [city]"),
                Arguments.of(
                        new UnknownTemplateVariable(),
                        "@McpCompletion argument 'town' is not declared by resource 'city://{city}' [city]"),
                Arguments.of(new MissingTarget(), "exactly one of prompt or resource"),
                Arguments.of(new BothTargets(), "exactly one of prompt or resource"),
                Arguments.of(new NoArgument(), "first String argument"),
                Arguments.of(new NumericPartial(), "first String argument"),
                Arguments.of(new MixedRequest(), "Unsupported parameter type"),
                Arguments.of(new BadReturn(), "CompletionResult or List<String>"),
                Arguments.of(new NonStringCandidates(), "CompletionResult or List<String>"),
                Arguments.of(new DuplicateTarget(), "Duplicate completion prompt 'trip'"),
                Arguments.of(new MixedFeatures(), "only one of @McpTool"));
    }

    @ParameterizedTest
    @MethodSource("invalidDeclarations")
    void rejectsInvalidDeclarationsAtBuildTime(Object service, String message) {
        assertThatIllegalStateException()
                .isThrownBy(TachyonServer.builder().annotations(annotations -> annotations.register(service))::build)
                .withMessageContaining(message);
    }

    @Test
    void rejectsUnknownArgumentOfPromptRegisteredByAnotherService() {
        assertThatIllegalStateException()
                .isThrownBy(() -> TachyonServer.builder()
                        .annotations(annotations ->
                                annotations.register(new TripPrompt()).register(new TownCompletion()))
                        .build())
                .withMessageContaining("@McpCompletion argument 'town' is not declared by prompt 'trip' [city]");
        try (var server = TachyonServer.builder()
                .annotations(annotations -> annotations.register(new TownCompletion()))
                .build()) {
            assertThat(server.prompts().find("trip")).isEmpty();
        }
    }

    static class TownCompletion {
        @McpCompletion(prompt = "trip")
        List<String> complete(String town) {
            return List.of();
        }
    }

    @Test
    void rejectsNamedSignatureCompiledWithoutParameterMetadata(@TempDir Path directory) throws Exception {
        final var source = directory.resolve("CompletionFixture.java");
        Files.writeString(source, """
                import dev.tachyonmcp.api.annotations.McpCompletion;
                import java.util.List;
                public class CompletionFixture {
                    @McpCompletion(prompt = "trip")
                    public List<String> complete(String city) { return List.of(city); }
                }
                """);
        final var compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler.run(
                        null,
                        null,
                        null,
                        "-proc:none",
                        "-classpath",
                        System.getProperty("java.class.path"),
                        "-d",
                        directory.toString(),
                        source.toString()))
                .isZero();
        try (var loader = new URLClassLoader(
                new URL[] {directory.toUri().toURL()}, getClass().getClassLoader())) {
            final var service =
                    loader.loadClass("CompletionFixture").getConstructor().newInstance();
            assertThatIllegalStateException()
                    .isThrownBy(() -> TachyonServer.builder()
                            .annotations(a -> a.register(service))
                            .build())
                    .withMessageContaining("Parameter names unavailable; compile with -parameters");
        }
    }
}
