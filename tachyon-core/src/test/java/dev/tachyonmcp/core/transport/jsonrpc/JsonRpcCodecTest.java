/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.jsonrpc;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.server.domain.RequestId;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonRpcCodecTest {

    @Test
    void serializeNotificationAsStringContainsRequiredFields() {
        var json = JsonRpcCodec.serializeNotificationAsString("notifications/tools/list_changed", "{}");

        // language=JSON
        assertThatJson(json).isEqualTo("""
            {
              "jsonrpc":"2.0",
              "method":"notifications/tools/list_changed",
              "params": {}
            }
            """);
    }

    @Test
    void serializeRequestAsStringContainsRequiredFields() {
        var json = JsonRpcCodec.serializeRequestAsString(
                RequestId.of("req-1"), "sampling/createMessage", "{\"prompt\":\"hi\"}");

        // language=JSON
        assertThatJson(json).isEqualTo("""
            {
              "jsonrpc":"2.0",
              "id":"req-1",
              "method":"sampling/createMessage",
              "params": {"prompt":"hi"}
            }
            """);
    }

    @Test
    void serializeNotificationAsStringWithNumericId() {
        var json = JsonRpcCodec.serializeRequestAsString(RequestId.of(99L), "ping", "{}");

        // language=JSON
        assertThatJson(json).isEqualTo("""
            {
              "jsonrpc":"2.0",
              "id":99,
              "method":"ping",
              "params": {}
            }
            """);
    }

    @Test
    void toJsonParamsReturnsEmptyObjectForNull() {
        assertThat(JsonRpcCodec.toJsonParams(null)).isEqualTo("{}");
    }

    @Test
    void toJsonParamsReturnsStringUnchanged() {
        assertThat(JsonRpcCodec.toJsonParams("already-serialized")).isEqualTo("already-serialized");
    }

    @Test
    void toJsonParamsSerializesObject() {
        var json = JsonRpcCodec.toJsonParams(java.util.Map.of("key", "value"));
        // language=json
        assertThatJson(json).isEqualTo("""
            {"key":"value"}
            """);
    }

    /**
     * The root-token check used to reject anything that was not {@code {} without reading the rest,
     * so a body that never was valid JSON — empty, whitespace-only, or an array cut short — was
     * classified as a well-formed envelope violation and earned {@code -32600} instead of
     * {@code -32700}.
     */
    @ParameterizedTest
    @ValueSource(strings = {"", "   \n\t ", "[1,2", "[{\"jsonrpc\":\"2.0\"", "{ not json", "nonsense"})
    void syntaxFailuresAreParseErrorsNotInvalidRequests(String body) {
        var parse = JsonRpcCodec.tryParseRequest(buf(body));

        assertThat(parse.message()).isNull();
        assertThat(parse.invalidRequest())
                .as("a body that is not valid JSON at all must answer -32700")
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "{\"hello\":\"world\"}",
                "[]",
                "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}]",
                "42",
                "\"text\"",
                "null"
            })
    void wellFormedJsonThatIsNotAnEnvelopeIsAnInvalidRequest(String body) {
        var parse = JsonRpcCodec.tryParseRequest(buf(body));

        assertThat(parse.message()).isNull();
        assertThat(parse.invalidRequest())
                .as("valid JSON in the wrong shape must answer -32600; batches are gone since 2025-06-18")
                .isTrue();
    }

    @Test
    void tryParseRequestReturnsTheMessageForAValidEnvelope() {
        var parse = JsonRpcCodec.tryParseRequest(buf("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"));

        assertThat(parse.invalidRequest()).isFalse();
        assertThat(parse.message()).isInstanceOfSatisfying(JsonRpcMessage.Request.class, request -> {
            assertThat(request.method()).isEqualTo("ping");
            assertThat(request.id()).isEqualTo(RequestId.of(1L));
        });
    }

    private static ByteBuf buf(String body) {
        return Unpooled.copiedBuffer(body, StandardCharsets.UTF_8);
    }
}
