/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.protocol.mcp.v2025_11_25.codecs;

import static dev.tachyonmcp.core.test.TestUtils.parseJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tachyonmcp.api.json.PayloadDeserializer;
import dev.tachyonmcp.api.server.domain.HasMeta;
import dev.tachyonmcp.api.server.domain.LoggingLevel;
import dev.tachyonmcp.api.server.domain.RequestId;
import dev.tachyonmcp.api.server.domain.ServerError;
import dev.tachyonmcp.api.server.features.tasks.TaskAwaitResultRequest;
import dev.tachyonmcp.api.server.features.tasks.TaskCancelRequest;
import dev.tachyonmcp.api.server.features.tasks.TaskGetRequest;
import dev.tachyonmcp.api.server.features.tasks.TaskState;
import dev.tachyonmcp.core.protocol.ProtocolRequestMapper;
import dev.tachyonmcp.core.protocol.RequestMappingException;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class McpRequestMapperTest {

    static Stream<Arguments> protocolMappers() {
        return Stream.of(
                Arguments.of("2025-11-25", new McpRequestMapper()),
                Arguments.of("2026-07-28", new dev.tachyonmcp.core.protocol.mcp.v2026_07_28.codecs.McpRequestMapper()));
    }

    private static final PayloadDeserializer NOOP_DESERIALIZER = new PayloadDeserializer() {
        @Override
        public <T> T deserialize(String json, Type targetType) {
            throw new UnsupportedOperationException("not used by these tests");
        }
    };

    @Test
    void concreteMapperConvertsPojoAndRejectsNonObjectParams() {
        var mapper = new McpRequestMapper();

        var prompt = mapper.getPrompt(new Params("greet", Map.of("trace", 7)));
        assertThat(prompt.name()).isEqualTo("greet");
        assertThat(prompt.request().arguments().asMap()).containsEntry("trace", 7);
        assertThatThrownBy(() -> mapper.resourceUri(List.of("not", "a", "map")))
                .isInstanceOf(RequestMappingException.class);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("protocolMappers")
    void promptAndCompletionRequestsPreserveMetadataInBothProtocolVersions(
            String ignoredProtocolVersion, ProtocolRequestMapper mapper) {
        var meta = Map.<String, Object>of("trace", Map.of("id", 7));

        var prompt = mapper.getPrompt(Map.of("name", "greet", "_meta", meta));
        var completion = mapper.complete(Map.of(
                "ref", Map.of("type", "ref/prompt", "name", "greet"),
                "argument", Map.of("name", "name", "value", "A"),
                "_meta", meta));

        assertThat(prompt.request().meta()).isEqualTo(meta);
        assertThat(completion.request().meta()).isEqualTo(meta);
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

    @ParameterizedTest
    @ValueSource(strings = {"get", "cancel", "result"})
    @SuppressWarnings("deprecation")
    void taskIdRequestsMapJsonTreeParams(String method) {
        final var mapper = new McpRequestMapper();
        final var params = parseJson("""
            {"taskId":"task-1","_meta":{"trace":7}}
            """);

        final Object request =
                switch (method) {
                    case "get" -> mapper.taskGet(params);
                    case "cancel" -> mapper.taskCancel(params);
                    case "result" -> mapper.taskAwaitResult(params);
                    default -> throw new AssertionError("Unexpected method " + method);
                };
        final var taskId =
                switch (request) {
                    case TaskGetRequest value -> value.taskId();
                    case TaskCancelRequest value -> value.taskId();
                    case TaskAwaitResultRequest value -> value.taskId();
                    default -> throw new AssertionError("Unexpected request " + request);
                };

        assertThat(taskId).isEqualTo("task-1");
        assertThat(((HasMeta) request).meta()).containsEntry("trace", 7L);
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

    @ParameterizedTest
    @EnumSource(LoggingLevel.class)
    void loggingLevelsMapFromJsonTree(LoggingLevel expected) {
        final var mapper = new McpRequestMapper();

        assertThat(mapper.loggingLevel(parseJson("""
                    {"level":"%s"}
                    """.formatted(expected.getValue()))))
                .isEqualTo(expected);
        assertThat(mapper.permittedLogLevel(parseJson("""
                    {"_meta":{"io.modelcontextprotocol/logLevel":"%s"}}
                    """.formatted(expected.getValue()))))
                .isEqualTo(expected);
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

    @Test
    void cancellationMapsNumericJsonRequestId() {
        final var request = new McpRequestMapper().cancellation(parseJson("""
            {"requestId":42,"reason":"cancelled"}
            """));

        assertThat(request).isNotNull();
        assertThat(request.requestId()).isEqualTo(RequestId.of(42L));
        assertThat(request.reason()).isEqualTo("cancelled");
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

    @Test
    void taskStatusDefaultsMissingJsonStatusAndMapsMessage() {
        final var request = new McpRequestMapper().taskStatus(parseJson("""
            {"taskId":"task-1","statusMessage":"Working"}
            """));

        assertThat(request).isNotNull();
        assertThat(request.state()).isEqualTo(TaskState.WORKING);
        assertThat(request.message()).isEqualTo("Working");
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

    private record Params(String name, Map<String, Object> arguments) {}
}
