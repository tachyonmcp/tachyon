/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.protocol.mcp.codec;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.codecs.CodecRegistry;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.CallToolRequestParams;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.CallToolResult;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.PaginatedRequestParams;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.Tool;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * A client may send an explicit JSON {@code null} for any optional property. The streaming
 * accessors either coerce it ({@code getString} yields the literal {@code "null"}) or throw
 * ({@code getBooleanValue}), so every generated decode goes through the null-tolerant helpers.
 */
class CodecNullToleranceTest {

    @Test
    void explicitNullStringDecodesToNullNotTheLiteralText() {
        var json = "{\"cursor\":null,\"limit\":null}".getBytes(StandardCharsets.UTF_8);

        var params = CodecRegistry.codecFor(PaginatedRequestParams.class).decodeFromBytes(json);

        assertThat(params.cursor()).isNull();
        assertThat(params._meta()).isNull();
    }

    @Test
    void explicitNullBooleanAndTreeFieldsDecodeToNull() {
        var json = "{\"content\":[],\"isError\":null,\"structuredContent\":null,\"resultType\":null}"
                .getBytes(StandardCharsets.UTF_8);

        var result = CodecRegistry.codecFor(CallToolResult.class).decodeFromBytes(json);

        assertThat(result.isError()).isNull();
        assertThat(result.structuredContent()).isNull();
        assertThat(result.resultType()).isNull();
        assertThat(result.content()).isEmpty();
    }

    @Test
    void decodeFromBytesReadsTreeValuedProperties() {
        var json = ("{\"name\":\"search\",\"arguments\":{\"q\":\"cats\",\"limit\":5},"
                        + "\"_meta\":{\"trace\":{\"id\":\"abc\"}},\"unknownExtra\":[1,2]}")
                .getBytes(StandardCharsets.UTF_8);

        var params = CodecRegistry.codecFor(CallToolRequestParams.class).decodeFromBytes(json);

        assertThat(params.name()).isEqualTo("search");
        assertThat(params.arguments()).containsKeys("q", "limit");
        assertThat(params.arguments().get("q").stringValue()).isEqualTo("cats");
        assertThat(params._meta()).containsKey("trace");
        assertThat(params._meta().get("trace").get("id").stringValue()).isEqualTo("abc");
    }

    @Test
    void rawJsonSchemaPropertyRoundTripsWithoutReserialization() {
        var json = ("{\"name\":\"search\",\"inputSchema\":{\"type\":\"object\","
                        + "\"properties\":{\"q\":{\"type\":\"string\"}}},\"outputSchema\":null}")
                .getBytes(StandardCharsets.UTF_8);

        var tool = CodecRegistry.codecFor(Tool.class).decodeFromBytes(json);

        assertThat(tool.inputSchema()).contains("\"type\":\"object\"").contains("\"q\"");
        assertThat(tool.outputSchema()).isNull();

        var encoded = new String(CodecRegistry.codecFor(Tool.class).encodeToBytes(tool), StandardCharsets.UTF_8);
        assertThat(encoded).contains("\"inputSchema\":{\"type\":\"object\"");
    }

    @Test
    void treeValuedPropertiesEncodeWithoutAnIntermediateString() {
        var json = "{\"content\":[],\"structuredContent\":{\"ok\":true,\"n\":1.5},\"_meta\":{\"a\":[1,2]}}"
                .getBytes(StandardCharsets.UTF_8);
        var codec = CodecRegistry.codecFor(CallToolResult.class);

        var encoded = new String(codec.encodeToBytes(codec.decodeFromBytes(json)), StandardCharsets.UTF_8);

        assertThat(encoded).contains("\"structuredContent\":{\"ok\":true,\"n\":1.5}");
        assertThat(encoded).contains("\"_meta\":{\"a\":[1,2]}");
    }
}
