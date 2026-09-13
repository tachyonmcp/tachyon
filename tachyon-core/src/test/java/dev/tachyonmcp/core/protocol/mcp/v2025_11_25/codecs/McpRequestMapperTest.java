/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.protocol.mcp.v2025_11_25.codecs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tachyonmcp.api.json.PayloadDeserializer;
import dev.tachyonmcp.api.server.domain.ServerError;
import dev.tachyonmcp.api.server.features.tasks.TaskState;
import dev.tachyonmcp.core.protocol.ProtocolRequestMapper;
import dev.tachyonmcp.core.protocol.RequestMappingException;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class McpRequestMapperTest {

    private static final PayloadDeserializer NOOP_DESERIALIZER = new PayloadDeserializer() {
        @Override
        public <T> T deserialize(String json, Type targetType) {
            throw new UnsupportedOperationException("not used by these tests");
        }
    };

    @Test
    void asObjectConvertsPojoAndFallsBackForNonObjects() {
        var mapper = new McpRequestMapper();

        var node = mapper.asObject(new Params("greet", Map.of("trace", 7)));
        assertThat(node.get("name").stringValue()).isEqualTo("greet");
        assertThat(node.get("meta").get("trace").intValue()).isEqualTo(7);
        assertThat(mapper.asObject(List.of("not", "a", "map")).isEmpty()).isTrue();
    }

    @Test
    void promptAndCompletionRequestsPreserveMetadataInBothProtocolVersions() {
        List<ProtocolRequestMapper> mappers = List.of(
                new McpRequestMapper(), new dev.tachyonmcp.core.protocol.mcp.v2026_07_28.codecs.McpRequestMapper());
        var meta = Map.<String, Object>of("trace", Map.of("id", 7));

        for (var mapper : mappers) {
            var prompt = mapper.getPrompt(Map.of("name", "greet", "_meta", meta));
            var completion = mapper.complete(Map.of(
                    "ref", Map.of("type", "ref/prompt", "name", "greet"),
                    "argument", Map.of("name", "name", "value", "A"),
                    "_meta", meta));

            assertThat(prompt.request().meta()).isEqualTo(meta);
            assertThat(completion.request().meta()).isEqualTo(meta);
        }
    }

    @Test
    void callToolMapsNameArgumentsAndTaskAugmentation() {
        var mapper = new McpRequestMapper();

        var result = mapper.callTool(
                Map.of(
                        "name", "greet",
                        "arguments", Map.of("who", "world"),
                        "_meta", Map.of("progressToken", "tok-1"),
                        "inputResponses", Map.of("answer", "Paris"),
                        "requestState", "round-1",
                        "task", Map.of("ttl", 5000)),
                NOOP_DESERIALIZER);

        assertThat(result.request().name()).isEqualTo("greet");
        assertThat(result.request().arguments().asMap()).containsEntry("who", "world");
        assertThat(result.request().progressToken()).isNotNull();
        assertThat(result.request().inputResponses()).containsEntry("answer", "Paris");
        assertThat(result.request().requestState()).isEqualTo("round-1");
        assertThat(result.taskAugmented()).isTrue();
        assertThat(result.taskTtl()).isEqualTo(Duration.ofMillis(5000));
    }

    @Test
    void callToolWithoutTaskIsNotTaskAugmented() {
        var mapper = new McpRequestMapper();

        var result = mapper.callTool(Map.of("name", "greet"), NOOP_DESERIALIZER);

        assertThat(result.taskAugmented()).isFalse();
        assertThat(result.taskTtl()).isNull();
    }

    @Test
    void callToolRejectsMissingName() {
        var mapper = new McpRequestMapper();

        assertThatThrownBy(() -> mapper.callTool(Map.of(), NOOP_DESERIALIZER))
                .isInstanceOf(RequestMappingException.class)
                .satisfies(e -> assertThat(((RequestMappingException) e).error().kind())
                        .isEqualTo(ServerError.Kind.INVALID_PARAMS));
    }

    @Test
    void callToolRejectsNonMapArguments() {
        var mapper = new McpRequestMapper();

        assertThatThrownBy(() -> mapper.callTool(Map.of("name", "greet", "arguments", "not-a-map"), NOOP_DESERIALIZER))
                .isInstanceOf(RequestMappingException.class);
    }

    @Test
    void readResourceMapsUriMetaAndInputResponses() {
        var mapper = new McpRequestMapper();

        var request = mapper.readResource(Map.of(
                "uri", "memory://doc",
                "inputResponses", Map.of("answer", "Paris"),
                "requestState", "round-1"));

        assertThat(request.uri()).isEqualTo("memory://doc");
        assertThat(request.inputResponses()).containsEntry("answer", "Paris");
        assertThat(request.requestState()).isEqualTo("round-1");
    }

    @Test
    void readResourceRejectsMissingUri() {
        var mapper = new McpRequestMapper();

        assertThatThrownBy(() -> mapper.readResource(Map.of())).isInstanceOf(RequestMappingException.class);
    }

    @Test
    void taskUpdateMapsTaskIdAndInputResponses() {
        var mapper = new McpRequestMapper();

        var request = mapper.taskUpdate(Map.of("taskId", "task-1", "inputResponses", Map.of("answer", "Paris")));

        assertThat(request.taskId()).isEqualTo("task-1");
        assertThat(request.inputResponses()).containsEntry("answer", "Paris");
    }

    @Test
    void taskUpdateRejectsMissingInputResponses() {
        var mapper = new McpRequestMapper();

        assertThatThrownBy(() -> mapper.taskUpdate(Map.of("taskId", "task-1")))
                .isInstanceOf(RequestMappingException.class)
                .satisfies(e -> assertThat(((RequestMappingException) e).error().kind())
                        .isEqualTo(ServerError.Kind.INVALID_PARAMS));
    }

    @Test
    void completeRejectsUnknownRefType() {
        var mapper = new McpRequestMapper();

        assertThatThrownBy(() -> mapper.complete(Map.of(
                        "ref", Map.of("type", "ref/unknown"),
                        "argument", Map.of("name", "n", "value", "v"))))
                .isInstanceOf(RequestMappingException.class);
    }

    @Test
    void completeRejectsNonStringContextArgumentValue() {
        var mapper = new McpRequestMapper();

        assertThatThrownBy(() -> mapper.complete(Map.of(
                        "ref", Map.of("type", "ref/prompt", "name", "greet"),
                        "argument", Map.of("name", "n", "value", "v"),
                        "context", Map.of("arguments", Map.of("prior", 123)))))
                .isInstanceOf(RequestMappingException.class)
                .satisfies(e -> assertThat(((RequestMappingException) e).error().kind())
                        .isEqualTo(ServerError.Kind.INVALID_PARAMS));
    }

    @Test
    void completePreservesStringContextArguments() {
        var mapper = new McpRequestMapper();

        var completion = mapper.complete(Map.of(
                "ref", Map.of("type", "ref/prompt", "name", "greet"),
                "argument", Map.of("name", "n", "value", "v"),
                "context", Map.of("arguments", Map.of("prior", "answer"))));

        assertThat(completion.request().resolvedArguments()).containsEntry("prior", "answer");
    }

    @Test
    void pageMapsLimitAndCursorDefaultingWhenAbsent() {
        var mapper = new McpRequestMapper();

        assertThat(mapper.page(Map.of("limit", 10, "cursor", "abc")))
                .isEqualTo(new ProtocolRequestMapper.PageRequest(10, "abc", null));
        assertThat(mapper.page(null)).isEqualTo(new ProtocolRequestMapper.PageRequest(0, null, null));
    }

    @Test
    void initializeMapsDeclaredExtensions() {
        var mapper = new McpRequestMapper();

        var request = mapper.initialize(Map.of(
                "capabilities", Map.of("extensions", Map.of("com.example/ext", Map.of("flag", true)))));

        assertThat(request.extensions()).containsOnlyKeys("com.example/ext");
    }

    @Test
    void initializeReturnsEmptyExtensionsWhenNotDeclared() {
        var mapper = new McpRequestMapper();

        assertThat(mapper.initialize(Map.of()).extensions()).isEmpty();
    }

    @Test
    void loggingLevelRejectsUnknownValue() {
        var mapper = new McpRequestMapper();

        assertThatThrownBy(() -> mapper.loggingLevel(Map.of("level", "not-a-level")))
                .isInstanceOf(RequestMappingException.class);
    }

    @Test
    void permittedLogLevelReturnsNullForUnknownValueInsteadOfThrowing() {
        var mapper = new McpRequestMapper();

        assertThat(mapper.permittedLogLevel(Map.of("_meta", Map.of("io.modelcontextprotocol/logLevel", "not-a-level"))))
                .isNull();
    }

    @Test
    void cancellationMapsRequestIdAndReason() {
        var mapper = new McpRequestMapper();

        var request = mapper.cancellation(Map.of("requestId", "req-1", "reason", "user cancelled"));

        assertThat(request).isNotNull();
        assertThat(request.reason()).isEqualTo("user cancelled");
    }

    @Test
    void cancellationReturnsNullForNonScalarRequestId() {
        var mapper = new McpRequestMapper();

        assertThat(mapper.cancellation(Map.of("requestId", Map.of("nested", true))))
                .isNull();
    }

    @ParameterizedTest
    @CsvSource({
        "input_required,INPUT_REQUIRED",
        "completed,COMPLETED",
        "failed,FAILED",
        "cancelled,CANCELLED",
        "some-unspecified-status,WORKING",
    })
    void taskStatusMapsKnownStatusStringsAndDefaultsUnknownOnesToWorking(String status, TaskState expected) {
        var mapper = new McpRequestMapper();

        var request = mapper.taskStatus(Map.of("taskId", "task-1", "status", status));

        assertThat(request).isNotNull();
        assertThat(request.state()).isEqualTo(expected);
    }

    @Test
    void taskStatusReturnsNullWhenTaskIdMissing() {
        var mapper = new McpRequestMapper();

        assertThat(mapper.taskStatus(Map.of("status", "completed"))).isNull();
    }

    /**
     * The {@code Codec} interface and registry are generated per protocol version, so a params type
     * that only exists in 2026-07-28 is invisible to the 2025-11-25 registry the superclass binds.
     */
    @Test
    void versionSpecificParamsDecodeThroughTheirOwnVersionsRegistry() {
        var mapper = new dev.tachyonmcp.core.protocol.mcp.v2026_07_28.codecs.McpRequestMapper();

        var empty = mapper.subscriptionsListen(Map.of());
        assertThat(empty.toolsListChanged()).isFalse();
        assertThat(empty.resourceSubscriptions()).isEmpty();

        var filtered = mapper.subscriptionsListen(
                Map.of("notifications", Map.of("toolsListChanged", true, "resourceSubscriptions", List.of("file:///a"))));
        assertThat(filtered.toolsListChanged()).isTrue();
        assertThat(filtered.resourceSubscriptions()).containsExactly("file:///a");
    }

    @Test
    void mismatchedPropertyTypesAreRejectedRatherThanDroppedSilently() {
        var mapper = new McpRequestMapper();

        assertThatThrownBy(() -> mapper.getPrompt(Map.of("name", "greet", "_meta", "not-an-object")))
                .isInstanceOf(RequestMappingException.class)
                .satisfies(e -> assertThat(((RequestMappingException) e).error().kind())
                        .isEqualTo(ServerError.Kind.INVALID_PARAMS));
        assertThatThrownBy(() -> mapper.initialize(Map.of("capabilities", List.of("wrong", "shape"))))
                .isInstanceOf(RequestMappingException.class);
    }

    /**
     * Params handed in as a map must survive the tree round-trip with their Java numeric width, or
     * an in-memory caller sees a different type than it passed.
     */
    @Test
    void inMemoryParamsPreserveNumericWidth() {
        var mapper = new McpRequestMapper();

        var request = mapper.getPrompt(Map.of("name", "greet", "_meta", Map.of("small", 7, "big", 7L)));

        assertThat(request.request().meta()).containsEntry("small", 7).containsEntry("big", 7L);
    }

    private record Params(String name, Map<String, Object> meta) {}
}
