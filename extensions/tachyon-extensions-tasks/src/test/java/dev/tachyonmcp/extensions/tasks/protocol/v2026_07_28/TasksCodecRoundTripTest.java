/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.tasks.protocol.v2026_07_28;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.extensions.tasks.protocol.v2026_07_28.codecs.CodecRegistry;
import dev.tachyonmcp.extensions.tasks.protocol.v2026_07_28.models.CancelledTask;
import dev.tachyonmcp.extensions.tasks.protocol.v2026_07_28.models.CompletedTask;
import dev.tachyonmcp.extensions.tasks.protocol.v2026_07_28.models.CreateTaskResult;
import dev.tachyonmcp.extensions.tasks.protocol.v2026_07_28.models.DetailedTask;
import dev.tachyonmcp.extensions.tasks.protocol.v2026_07_28.models.FailedTask;
import dev.tachyonmcp.extensions.tasks.protocol.v2026_07_28.models.GetTaskRequest;
import dev.tachyonmcp.extensions.tasks.protocol.v2026_07_28.models.GetTaskResult;
import dev.tachyonmcp.extensions.tasks.protocol.v2026_07_28.models.InputRequiredTask;
import dev.tachyonmcp.extensions.tasks.protocol.v2026_07_28.models.TaskStatus;
import dev.tachyonmcp.extensions.tasks.protocol.v2026_07_28.models.TaskStatusNotification;
import dev.tachyonmcp.extensions.tasks.protocol.v2026_07_28.models.WorkingTask;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Generated tasks codecs decode and re-encode wire JSON losslessly, resolving core MCP types
 * ({@code Error}, {@code InputRequests}) through the core codec registry.
 */
class TasksCodecRoundTripTest {

    private static final String TIMES =
            "\"createdAt\":\"2026-07-28T10:00:00Z\",\"lastUpdatedAt\":\"2026-07-28T10:00:05Z\"";

    @Test
    void detailedTaskVariantsRoundTripByStatus() {
        var working = "{\"taskId\":\"t1\",\"status\":\"working\",\"statusMessage\":\"50%\"," + TIMES
                + ",\"ttlMs\":60000,\"pollIntervalMs\":500}";
        var inputRequired = "{\"taskId\":\"t2\",\"status\":\"input_required\"," + TIMES + ",\"ttlMs\":null,"
                + "\"inputRequests\":{\"ask\":{\"method\":\"elicitation/create\",\"params\":{}}}}";
        var completed = "{\"taskId\":\"t3\",\"status\":\"completed\"," + TIMES + ",\"ttlMs\":null,"
                + "\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"done\"}]}}";
        var failed = "{\"taskId\":\"t4\",\"status\":\"failed\"," + TIMES + ",\"ttlMs\":1000,"
                + "\"error\":{\"code\":-32603,\"message\":\"boom\",\"data\":{\"retry\":false}}}";
        var cancelled = "{\"taskId\":\"t5\",\"status\":\"cancelled\"," + TIMES + ",\"ttlMs\":null}";

        assertThat(roundTrip(working)).isInstanceOfSatisfying(WorkingTask.class, t -> {
            assertThat(t.taskId()).isEqualTo("t1");
            assertThat(t.createdAt()).isEqualTo(Instant.parse("2026-07-28T10:00:00Z"));
            assertThat(t.ttlMs()).isEqualTo(60_000L);
            assertThat(t.pollIntervalMs()).isEqualTo(500L);
        });
        assertThat(roundTrip(inputRequired)).isInstanceOfSatisfying(InputRequiredTask.class, t -> {
            assertThat(t.ttlMs()).isNull();
            assertThat(t.inputRequests().additionalProperties().propertyNames()).containsOnly("ask");
        });
        assertThat(roundTrip(completed))
                .isInstanceOfSatisfying(
                        CompletedTask.class,
                        t -> assertThat(t.result().propertyNames()).containsOnly("content"));
        assertThat(roundTrip(failed)).isInstanceOfSatisfying(FailedTask.class, t -> {
            assertThat(t.error().message()).isEqualTo("boom");
            assertThat(t.error().code()).isEqualTo(-32603);
        });
        assertThat(roundTrip(cancelled)).isInstanceOf(CancelledTask.class);
    }

    @Test
    void createTaskResultCarriesTaskFieldsMetaAndResultType() {
        var json = "{\"taskId\":\"t1\",\"status\":\"working\"," + TIMES
                + ",\"ttlMs\":null,\"resultType\":\"task\",\"_meta\":{\"trace\":\"abc\"}}";
        var codec = CodecRegistry.codecFor(CreateTaskResult.class);

        var result = codec.decodeFromBytes(bytes(json));

        assertThat(result.status()).isEqualTo(TaskStatus.WORKING);
        assertThat(result.resultType()).isEqualTo("task");
        assertThat(result._meta().propertyNames()).containsOnly("trace");
        assertThat(result.ttlMs()).isNull();
        assertThatJson(text(codec.encodeToBytes(result))).isEqualTo(json);
    }

    @Test
    void getTaskRequestKeepsJsonRpcEnvelopeParamsAndIdType() {
        var numericId = "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tasks/get\",\"params\":{\"taskId\":\"t1\"}}";
        var stringId = "{\"jsonrpc\":\"2.0\",\"id\":\"7\",\"method\":\"tasks/get\",\"params\":{\"taskId\":\"t1\"}}";
        var codec = CodecRegistry.codecFor(GetTaskRequest.class);

        var numeric = codec.decodeFromBytes(bytes(numericId));
        var string = codec.decodeFromBytes(bytes(stringId));

        assertThat(numeric.method()).isEqualTo("tasks/get");
        assertThat(numeric.params().taskId()).isEqualTo("t1");
        assertThat(numeric.id().isNumber()).isTrue();
        assertThat(numeric.id().intValue()).isEqualTo(7);
        assertThat(string.id().stringValue()).isEqualTo("7");
        assertThat(text(codec.encodeToBytes(numeric))).contains("\"id\":7");
        assertThatJson(text(codec.encodeToBytes(numeric))).isEqualTo(numericId);
        assertThatJson(text(codec.encodeToBytes(string))).isEqualTo(stringId);
    }

    @Test
    void getTaskResultInlinesVariantNextToResultTypeAndMeta() {
        var json = "{\"taskId\":\"t4\",\"status\":\"failed\"," + TIMES + ",\"ttlMs\":1000,"
                + "\"error\":{\"code\":-32603,\"message\":\"boom\"},"
                + "\"resultType\":\"complete\",\"_meta\":{\"trace\":\"abc\"}}";
        var codec = CodecRegistry.codecFor(GetTaskResult.class);

        var result = codec.decodeFromBytes(bytes(json));

        assertThat(result.resultType()).isEqualTo("complete");
        assertThat(result._meta().propertyNames()).containsOnly("trace");
        assertThat(result.detailedTask()).isInstanceOfSatisfying(FailedTask.class, t -> {
            assertThat(t.taskId()).isEqualTo("t4");
            assertThat(t.ttlMs()).isEqualTo(1000L);
            assertThat(t.error().message()).isEqualTo("boom");
        });
        assertThatJson(text(codec.encodeToBytes(result))).withTolerance(0).isEqualTo(json);
    }

    @Test
    void taskStatusNotificationKeepsVariantMetaAndExtraParams() {
        var json = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/tasks\",\"params\":"
                + "{\"taskId\":\"t5\",\"status\":\"cancelled\"," + TIMES + ",\"ttlMs\":null,"
                + "\"_meta\":{\"io.modelcontextprotocol/subscriptionId\":\"s1\"},\"custom\":1}}";
        var codec = CodecRegistry.codecFor(TaskStatusNotification.class);

        var notification = codec.decodeFromBytes(bytes(json));

        var params = notification.params();
        assertThat(params.detailedTask())
                .isInstanceOfSatisfying(
                        CancelledTask.class, t -> assertThat(t.taskId()).isEqualTo("t5"));
        assertThat(params._meta().propertyNames()).containsOnly("io.modelcontextprotocol/subscriptionId");
        assertThat(params.additionalProperties().propertyNames()).containsOnly("custom");
        assertThat(params.additionalProperties().get("custom").intValue()).isEqualTo(1);
        assertThatJson(text(codec.encodeToBytes(notification))).isEqualTo(json);
    }

    private static DetailedTask roundTrip(String json) {
        var codec = CodecRegistry.codecFor(DetailedTask.class);
        var task = codec.decodeFromBytes(bytes(json));
        assertThatJson(text(codec.encodeToBytes(task))).withTolerance(0).isEqualTo(json);
        return task;
    }

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] json) {
        return new String(json, StandardCharsets.UTF_8);
    }
}
