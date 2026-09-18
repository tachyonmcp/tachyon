/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.annotations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import dev.tachyonmcp.api.annotations.McpParam;
import dev.tachyonmcp.api.annotations.McpPrompt;
import dev.tachyonmcp.api.annotations.McpResource;
import dev.tachyonmcp.api.annotations.McpTool;
import dev.tachyonmcp.api.annotations.Meta;
import dev.tachyonmcp.core.server.TachyonServer;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Registration-time contract of {@link TachyonAnnotationProvider}: invalid declarations fail
 * {@code build()} fast instead of surfacing on first call. Wire behaviour lives in the e2e
 * {@code DeclarativeFeaturesTest}.
 */
class TachyonAnnotationProviderTest {

    private static TachyonServer build(Object service) {
        return TachyonServer.builder().annotations(a -> a.register(service)).build();
    }

    @SuppressWarnings("unused")
    static class Plain {
        @McpTool
        String echo(String text) {
            return text;
        }

        @McpResource(uri = "plain://readme")
        String readme() {
            return "hi";
        }

        @McpPrompt
        String ask(String question) {
            return question;
        }
    }

    static class PlainSubclass extends Plain {}

    @Test
    void registersEveryFeatureKindWithMethodNameDefaults() {
        try (var server = build(new PlainSubclass())) {
            assertThat(server.tools().find("echo")).isPresent();
            assertThat(server.resources().findByUri("plain://readme"))
                    .hasValueSatisfying(r -> assertThat(r.name()).isEqualTo("readme"));
            assertThat(server.prompts().find("ask")).isPresent();
        }
    }

    @Test
    void declaresFeaturesSeesInheritedMethodsOnly() {
        assertThat(TachyonAnnotationProvider.declaresFeatures(PlainSubclass.class))
                .isTrue();
        assertThat(TachyonAnnotationProvider.declaresFeatures(String.class)).isFalse();
    }

    @SuppressWarnings("unused")
    static class TwoAnnotations {
        @McpTool
        @McpPrompt
        String both() {
            return "";
        }
    }

    @SuppressWarnings("unused")
    static class DuplicateTools {
        @McpTool(name = "same")
        String first() {
            return "";
        }

        @McpTool(name = "same")
        String second() {
            return "";
        }
    }

    @SuppressWarnings("unused")
    static class PrivateTool {
        @McpTool
        private String hidden() {
            return "";
        }
    }

    @SuppressWarnings("unused")
    static class StaticResourceWithArgument {
        @McpResource(uri = "plain://static")
        String read(String id) {
            return id;
        }
    }

    @SuppressWarnings("unused")
    static class TemplateMismatch {
        @McpResource(uri = "plain://items/{id}")
        String read(String name) {
            return name;
        }
    }

    record Query(String text) {}

    static class UnusedTemplateVariable {
        @McpResource(uri = "plain://items/{id}/{day}")
        String read(String id) {
            return id;
        }
    }

    static class TemplateWithoutArguments {
        @McpResource(uri = "plain://items/{id}")
        String read() {
            return "item";
        }
    }

    @Test
    void rejectsTemplateVariablesWithoutParametersAtBuildTime() {
        assertThatIllegalStateException()
                .isThrownBy(() -> build(new UnusedTemplateVariable()))
                .withMessageContaining("[id] must match URI template variables [id, day]");
        assertThatIllegalStateException()
                .isThrownBy(() -> build(new TemplateWithoutArguments()))
                .withMessageContaining("[] must match URI template variables [id]");
    }

    @SuppressWarnings("unused")
    static class PromptWithRecord {
        @McpPrompt
        String ask(Query query) {
            return query.text();
        }
    }

    @Test
    void rejectsMoreThanOneFeatureAnnotationPerMethod() {
        assertThatIllegalStateException()
                .isThrownBy(() -> build(new TwoAnnotations()))
                .withMessageContaining("only one of @McpTool, @McpResource, @McpPrompt");
    }

    @Test
    void rejectsDuplicateToolNames() {
        assertThatIllegalStateException()
                .isThrownBy(() -> build(new DuplicateTools()))
                .withMessageContaining("Duplicate tool 'same'");
    }

    @Test
    void rejectsPrivateMethods() {
        assertThatIllegalStateException()
                .isThrownBy(() -> build(new PrivateTool()))
                .withMessageContaining("must not be private");
    }

    @Test
    void rejectsArgumentsOnStaticResource() {
        assertThatIllegalStateException()
                .isThrownBy(() -> build(new StaticResourceWithArgument()))
                .withMessageContaining("must not declare arguments [id]");
    }

    @Test
    void rejectsTemplateParametersThatAreNotTemplateVariables() {
        assertThatIllegalStateException()
                .isThrownBy(() -> build(new TemplateMismatch()))
                .withMessageContaining("[name] must match URI template variables [id]");
    }

    @Test
    void rejectsNonScalarPromptArguments() {
        assertThatIllegalStateException()
                .isThrownBy(() -> build(new PromptWithRecord()))
                .withMessageContaining("Unsupported parameter type");
    }

    private record Payload(String city, int days) {}

    private record Trace(String id) {}

    @SuppressWarnings("unused")
    static class ParamAndMeta {
        @McpTool
        String named(
                @McpParam(name = "q", description = "Search text") String arg0,
                @McpParam(description = "Row cap") @Nullable Integer limit) {
            return arg0;
        }

        @McpTool
        String forcedNamed(@McpParam(name = "payload") Payload payload) {
            return payload.city();
        }

        @McpTool
        String whole(Payload payload) {
            return payload.city();
        }

        @McpTool
        String rawMeta(String text, @Meta Map<String, Object> meta) {
            return text;
        }

        @McpTool
        String typedMeta(String text, @Meta @Nullable Trace trace) {
            return text;
        }

        @McpPrompt
        String prompt(@McpParam(name = "topic", description = "What to ask") String subject, @Meta Trace trace) {
            return subject;
        }

        @McpResource(uri = "doc://{id}")
        String doc(@McpParam(name = "id") String docId, @Meta Map<String, Object> meta) {
            return docId;
        }
    }

    @Test
    void mcpParamRenamesAndDescribesToolArguments() {
        try (var server = build(new ParamAndMeta())) {
            var schema = server.tools().find("named").orElseThrow().inputSchema();
            assertThat(schema.toString())
                    .contains("\"q\"")
                    .contains("Search text")
                    .contains("Row cap")
                    .doesNotContain("\"arg0\"");
            assertThat(schema.toString()).contains("\"required\":[\"q\"]");
        }
    }

    @Test
    void mcpParamOnSingleObjectForcesNamedBinding() {
        try (var server = build(new ParamAndMeta())) {
            var forced = server.tools()
                    .find("forcedNamed")
                    .orElseThrow()
                    .inputSchema()
                    .json();
            assertThat(forced).contains("\"payload\"").contains("\"city\"");
            var whole = server.tools().find("whole").orElseThrow().inputSchema().json();
            assertThat(whole).doesNotContain("\"payload\"").contains("\"city\"");
        }
    }

    @Test
    void metaParameterIsExcludedFromSchemaAndPromptArguments() {
        try (var server = build(new ParamAndMeta())) {
            assertThat(server.tools()
                            .find("rawMeta")
                            .orElseThrow()
                            .inputSchema()
                            .json())
                    .doesNotContain("meta");
            assertThat(server.tools()
                            .find("typedMeta")
                            .orElseThrow()
                            .inputSchema()
                            .json())
                    .doesNotContain("trace");
            var prompt = server.prompts().find("prompt").orElseThrow();
            assertThat(prompt.arguments()).hasSize(1);
            assertThat(prompt.arguments().getFirst().name()).isEqualTo("topic");
            assertThat(prompt.arguments().getFirst().description()).isEqualTo("What to ask");
            assertThat(server.resources().templateDescriptors())
                    .anySatisfy(t -> assertThat(t.uriTemplate()).isEqualTo("doc://{id}"));
        }
    }

    @SuppressWarnings("unused")
    static class TwoMetas {
        @McpTool
        String bad(@Meta Map<String, Object> a, @Meta Trace b) {
            return "";
        }
    }

    @Test
    void rejectsTwoMetaParameters() {
        assertThatIllegalStateException()
                .isThrownBy(() -> build(new TwoMetas()))
                .withMessageContaining("only one @Meta");
    }
}
