/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.jsonrpc;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import dev.tachyonmcp.api.server.domain.RequestId;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

class JsonRpcCodecTest {

    @Test
    void parseRequestKeepsIntegersWiderThanLongAndWidensTheRest() {
        // language=JSON
        var json = """
            {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"big":12345678901234567890,"small":7,"ratio":0.5}}
            """;

        var message = JsonRpcCodec.parseRequest(Unpooled.copiedBuffer(json, StandardCharsets.UTF_8));

        assertThat(message).isInstanceOfSatisfying(JsonRpcMessage.Request.class, request -> {
            var params = (JsonNode) request.params();
            assertThat(params.get("big").bigIntegerValue()).isEqualTo(new BigInteger("12345678901234567890"));
            assertThat(params.get("small").isLong()).isTrue();
            assertThat(params.get("ratio").doubleValue()).isEqualTo(0.5);
        });
    }

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
    void floatsAreWrittenAsShortestFloatNumbers() {
        assertThat(JsonRpcCodec.writeValueAsString(0.1f)).isEqualTo("0.1");
        assertThat(JsonRpcCodec.writeValueAsString(java.util.List.of(1.5f, -0.25f, Float.MAX_VALUE)))
                .isEqualTo("[1.5,-0.25,3.4028235E38]");
        assertThat(JsonRpcCodec.writeValueAsString(java.util.Map.of("ratio", 0.1f)))
                .isEqualTo("{\"ratio\":0.1}");
    }

    @Test
    void readValueTurnsJsonIntoMapsListsAndScalars() {
        // language=JSON
        var json = """
            {"count":1,"ratio":1.5,"enabled":true,"name":"tachyon","nothing":null,"tags":["a","b"],"nested":{}}
            """;

        var value = JsonRpcCodec.readValue(json);

        assertThat(value)
                .asInstanceOf(InstanceOfAssertFactories.map(String.class, Object.class))
                .containsEntry("count", 1L)
                .containsEntry("ratio", 1.5)
                .containsEntry("enabled", true)
                .containsEntry("name", "tachyon")
                .containsEntry("nothing", null)
                .containsEntry("tags", List.of("a", "b"))
                .containsEntry("nested", Map.of());
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

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{'id':1,'method':'ping'}",
                "{'jsonrpc':2.0,'id':1,'method':'ping'}",
                "{'jsonrpc':null,'id':1,'method':'ping'}",
                "{'jsonrpc':'2.0','jsonrpc':'2.0','id':1,'method':'ping'}",
                "{'jsonrpc':'2.0','id':1,'method':5}",
                "{'jsonrpc':'2.0','id':1,'method':null}",
                "{'jsonrpc':'2.0','id':1,'method':{}}",
                "{'jsonrpc':'2.0','id':true,'method':'ping'}",
                "{'jsonrpc':'2.0','id':{},'method':'ping'}",
                "{'jsonrpc':'2.0','id':[],'method':'ping'}",
                "{'jsonrpc':'2.0','id':null,'method':'ping'}",
                "{'jsonrpc':'2.0','id':null,'result':{}}",
                "{'jsonrpc':'2.0','id':null,'error':{'code':-32600,'message':'bad'}}",
                "{'jsonrpc':'2.0','id':1,'id':2,'method':'ping'}",
                "{'jsonrpc':'2.0','id':1,'method':'ping','method':'tools/call'}",
                "{'jsonrpc':'2.0','id':1,'method':'ping','params':{},'params':{}}",
                "{'jsonrpc':'2.0','id':1,'method':'ping','result':{}}",
                "{'jsonrpc':'2.0','id':1,'result':{},'method':'ping'}",
                "{'jsonrpc':'2.0','id':1,'method':'ping','error':{'code':1,'message':'x'}}",
                "{'jsonrpc':'2.0','id':1,'result':{},'error':{'code':1,'message':'x'}}",
                "{'jsonrpc':'2.0','id':1}",
                "{'jsonrpc':'2.0','result':{}}",
                "{'jsonrpc':'2.0','id':1,'error':5}",
                "{'jsonrpc':'2.0','id':1,'error':[]}",
                "{'jsonrpc':'2.0','id':1,'error':{}}",
                "{'jsonrpc':'2.0','id':1,'error':{'message':'x'}}",
                "{'jsonrpc':'2.0','id':1,'error':{'code':1}}",
                "{'jsonrpc':'2.0','id':1,'error':{'code':'1','message':'x'}}",
                "{'jsonrpc':'2.0','id':1,'error':{'code':1.5,'message':'x'}}",
                "{'jsonrpc':'2.0','id':1,'error':{'code':99999999999,'message':'x'}}",
                "{'jsonrpc':'2.0','id':1,'error':{'code':1,'message':5}}",
                "{'jsonrpc':'2.0','id':1,'method':'ping','params':5}",
                "{'jsonrpc':'2.0','id':1,'method':'ping','params':'x'}",
                "{'jsonrpc':'2.0','id':1,'method':'ping','params':true}",
                "{'jsonrpc':'9.9'} x",
                "{'jsonrpc':'9.9','id':}",
                "{'id':1,'method':'ping'} x",
            })
    void envelopeViolationsAreInvalidRequestsAndTheFirstOneWins(String body) {
        var parse = JsonRpcCodec.tryParseRequest(json(body));

        assertThat(parse.message()).isNull();
        assertThat(parse.invalidRequest())
                .as("valid JSON that breaks the JSON-RPC envelope must answer -32600")
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{'jsonrpc':'2.0','id':1,'method':'ping'} garbage",
                "{'jsonrpc':'2.0','id':1,'method':'ping'}{'jsonrpc':'2.0','id':2,'method':'ping'}",
                "{'jsonrpc':'2.0','id':1,'method':'ping'}[",
                "{'jsonrpc':'2.0','id':1,'method':'ping'},",
            })
    void contentAfterTheRootObjectIsAParseError(String body) {
        var parse = JsonRpcCodec.tryParseRequest(json(body));

        assertThat(parse.message()).isNull();
        assertThat(parse.invalidRequest())
                .as("more than one JSON text is not JSON at all: -32700")
                .isFalse();
    }

    static Stream<Arguments> validEnvelopes() {
        var nodes = JsonNodeFactory.instance;
        return Stream.of(
                arguments(
                        "{'jsonrpc':'2.0','method':'notifications/initialized'}\n ",
                        new JsonRpcMessage.Notification<>("notifications/initialized", null)),
                arguments(
                        "{'jsonrpc':'2.0','method':'notifications/x','params':null}",
                        new JsonRpcMessage.Notification<>("notifications/x", null)),
                arguments(
                        "{'jsonrpc':'2.0','id':1,'method':'ping','params':null}",
                        new JsonRpcMessage.Request<>(RequestId.of(1L), "ping", null)),
                arguments(
                        "{'x':{'y':[1,{'z':2}]},'jsonrpc':'2.0','id':'a','method':'m','params':{}}",
                        new JsonRpcMessage.Request<>(RequestId.of("a"), "m", nodes.objectNode())),
                arguments(
                        "{'jsonrpc':'2.0','id':1,'method':'m','params':[]}",
                        new JsonRpcMessage.Request<>(RequestId.of(1L), "m", nodes.arrayNode())),
                arguments(
                        "{'jsonrpc':'2.0','id':7,'result':{'a':1}}",
                        new JsonRpcMessage.Response(RequestId.of(7L), "{\"a\":1}")),
                arguments(
                        "{'jsonrpc':'2.0','id':7,'result':null}",
                        new JsonRpcMessage.Response(RequestId.of(7L), "null")),
                arguments(
                        "{'jsonrpc':'2.0','id':3,'error':{'code':-32602,'message':'bad','data':{'k':[1]},'x':1}}",
                        new JsonRpcMessage.Error(RequestId.of(3L), -32602, "bad", "{\"k\":[1]}")),
                arguments(
                        "{'jsonrpc':'2.0','error':{'code':-32700,'message':'Parse error'}}",
                        new JsonRpcMessage.Error(null, -32700, "Parse error", null)));
    }

    @ParameterizedTest
    @MethodSource("validEnvelopes")
    void validEnvelopesParse(String body, JsonRpcMessage expected) {
        var parse = JsonRpcCodec.tryParseRequest(json(body));

        assertThat(parse.invalidRequest()).isFalse();
        assertThat(parse.message()).isEqualTo(expected);
    }

    private static ByteBuf buf(String body) {
        return Unpooled.copiedBuffer(body, StandardCharsets.UTF_8);
    }

    private static ByteBuf json(String singleQuoted) {
        return buf(singleQuoted.replace('\'', '"'));
    }
}
