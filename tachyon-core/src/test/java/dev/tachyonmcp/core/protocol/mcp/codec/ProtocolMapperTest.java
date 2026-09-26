/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.protocol.mcp.codec;

import static dev.tachyonmcp.core.test.TestUtils.properties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.codecs.CodecRegistry;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.codecs.ProtocolCodecUtil;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.CallToolRequestParams;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.CallToolResult;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ContentBlock;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.CreateMessageResult;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.EmptyResult;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.Implementation;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.InitializeRequestParams;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.InitializeResult;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ListToolsResult;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.Role;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ServerCapabilities;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.TextContent;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.Tool;
import dev.tachyonmcp.core.transport.jsonrpc.JsonRpcCodec;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.node.JsonNodeFactory;

class ProtocolMapperTest {

    @Test
    void serializeEmptyResult() {
        var bytes = CodecRegistry.codecFor(EmptyResult.class).encodeToBytes(new EmptyResult(null, null));
        var json = new String(bytes, StandardCharsets.UTF_8);
        assertThat(json).doesNotContain("_meta").doesNotContain("additionalProperties");
    }

    @Test
    void serializeToolResultContent() {
        var text = TextContent.of("hello");
        var content = List.<ContentBlock>of(text);
        var result = new CallToolResult(content, null, null, null, null);

        var bytes = CodecRegistry.codecFor(CallToolResult.class).encodeToBytes(result);
        var json = new String(bytes, StandardCharsets.UTF_8);
        assertThat(json).contains("\"content\"");
        assertThat(json).contains("\"text\"");
        assertThat(json).contains("\"hello\"");
    }

    @Test
    void serializesOpenStringStopReason() {
        var result =
                new CreateMessageResult("test", "provider-stop", null, Role.ASSISTANT, TextContent.of("done"), null);

        var json = new String(
                CodecRegistry.codecFor(CreateMessageResult.class).encodeToBytes(result), StandardCharsets.UTF_8);

        assertThat(json).contains("\"stopReason\":\"provider-stop\"");
    }

    @Test
    void serializeInitializeResult() {
        var caps = new ServerCapabilities(null, null, null, null, null, null, null, null);
        var info = new Implementation("1.0", null, null, "test", null, null);
        var result = new InitializeResult("2025-11-25", caps, info, null, null, null);

        var bytes = CodecRegistry.codecFor(InitializeResult.class).encodeToBytes(result);
        var json = new String(bytes, StandardCharsets.UTF_8);
        assertThat(json).contains("\"2025-11-25\"");
        assertThat(json).contains("\"test\"");
    }

    @Test
    void serializeListToolsResult() {
        var tool = new Tool("My tool", "{\"type\":\"object\"}", null, null, null, null, "my-tool", null, null);
        var result = new ListToolsResult(List.of(tool), null, null, null);

        var bytes = CodecRegistry.codecFor(ListToolsResult.class).encodeToBytes(result);
        var json = new String(bytes, StandardCharsets.UTF_8);
        assertThat(json).contains("\"my-tool\"");
        assertThat(json).contains("\"My tool\"");
    }

    @Test
    void deserializeCallToolRequestParams() {
        var map = Map.of("name", "my-tool", "arguments", Map.of("key", "value"));

        var json = JsonRpcCodec.writeValueAsString(map);
        var params = ProtocolCodecUtil.decodeWithCodec(json, CallToolRequestParams.class);
        assertThat(params.name()).isEqualTo("my-tool");
        assertThat(properties(params.arguments())).containsKey("key");
    }

    @Test
    void deserializeInitializeRequestParams() {
        var map =
                Map.of("protocolVersion", "2025-11-25", "clientInfo", Map.of("name", "test-client", "version", "1.0"));

        var json = JsonRpcCodec.writeValueAsString(map);
        var params = ProtocolCodecUtil.decodeWithCodec(json, InitializeRequestParams.class);
        assertThat(params.protocolVersion()).isEqualTo("2025-11-25");
        assertThat(params.clientInfo()).isNotNull();
        assertThat(params.clientInfo().name()).isEqualTo("test-client");
    }

    @Test
    void deserializeWithUnknownFields() {
        var map = Map.<String, Object>of(
                "name", "tool",
                "unknownField", "shouldBeIgnored");
        var json = JsonRpcCodec.writeValueAsString(map);
        var params = ProtocolCodecUtil.decodeWithCodec(json, CallToolRequestParams.class);
        assertThat(params.name()).isEqualTo("tool");
    }

    @Test
    void nonObjectInputFailsWithJacksonMismatch() {
        assertThatThrownBy(() -> ProtocolCodecUtil.decodeWithCodec("[1]", CallToolRequestParams.class))
                .isInstanceOf(MismatchedInputException.class)
                .hasMessageContaining("Expected JSON object for CallToolRequestParams");
        assertThatThrownBy(() -> CodecRegistry.codecFor(CallToolRequestParams.class)
                        .decodeFromBytes("\"tool\"".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(MismatchedInputException.class)
                .hasMessageContaining("Expected JSON object");
    }

    @Test
    void nullRemainsNull() {
        var json = JsonRpcCodec.writeValueAsString(null);
        assertThat(json).isEqualTo("null");
    }

    @Test
    void initializeResultIncludesLoggingAndCompletionsCapabilities() {
        var empty = JsonNodeFactory.instance.objectNode();
        var caps = new ServerCapabilities(null, empty, empty, null, null, null, null, null);
        var info = new Implementation("1.0", null, null, "test", null, null);
        var result = new InitializeResult("2025-11-25", caps, info, null, null, null);

        var bytes = CodecRegistry.codecFor(InitializeResult.class).encodeToBytes(result);
        var json = new String(bytes, StandardCharsets.UTF_8);
        assertThat(json).contains("\"logging\"");
        assertThat(json).contains("\"completions\"");
    }

    @Test
    void initializeResultExcludesLoggingAndCompletionsWhenNull() {
        var caps = new ServerCapabilities(null, null, null, null, null, null, null, null);
        var info = new Implementation("1.0", null, null, "test", null, null);
        var result = new InitializeResult("2025-11-25", caps, info, null, null, null);

        var bytes = CodecRegistry.codecFor(InitializeResult.class).encodeToBytes(result);
        var json = new String(bytes, StandardCharsets.UTF_8);
        assertThat(json).doesNotContain("\"logging\"");
        assertThat(json).doesNotContain("\"completions\"");
    }
}
