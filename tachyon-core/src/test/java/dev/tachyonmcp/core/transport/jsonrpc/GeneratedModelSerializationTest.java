/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.jsonrpc;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.core.protocol.codec.Codec;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.codecs.CodecRegistry;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ModelHint;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.CompleteResult;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.TextContent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Generated protocol models reaching the generic serializer (e.g. inside a map an extension
 * handler returns) are written by their generated codec, not as their {@code toString()}.
 */
class GeneratedModelSerializationTest {

    @Test
    void generatedModelsNestedInMapsAndListsUseTheirCodecs() {
        var current = new TextContent("text", "hello", null, JsonNodeFactory.instance.objectNode());
        var legacy = new dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.TextContent("text", "hi", null, null);
        var nested = new CompleteResult.Completion(List.of("a", "b"), 2L, null);

        var json = JsonRpcCodec.writeValueAsString(
                Map.of("content", List.of(current, legacy), "completion", nested, "resultType", "complete"));

        assertThatJson(json).isEqualTo("""
                        {
                          "content": [
                            {"type": "text", "text": "hello", "_meta": {}},
                            {"type": "text", "text": "hi"}
                          ],
                          "completion": {"values": ["a", "b"], "total": 2},
                          "resultType": "complete"
                        }""");
    }

    @Test
    void runtimeOverrideWithNonPublicCodecIsUsed() {
        var generated = CodecRegistry.codecFor(ModelHint.class);
        CodecRegistry.registerOverride(ModelHint.class, new Codec<>() {
            @Override
            public ModelHint decode(JsonParser parser) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void encode(JsonGenerator gen, ModelHint value) {
                gen.writeString("hint:" + value.name());
            }
        });
        try {
            assertThat(JsonRpcCodec.writeValueAsString(List.of(new ModelHint("claude"))))
                    .isEqualTo("[\"hint:claude\"]");
        } finally {
            CodecRegistry.registerOverride(ModelHint.class, generated);
        }
        assertThatJson(JsonRpcCodec.writeValueAsString(new ModelHint("claude"))).isEqualTo("{\"name\":\"claude\"}");
    }

    @Test
    void otherObjectsKeepTheirStringForm() {
        assertThat(JsonRpcCodec.writeValueAsString(new StringBuilder("plain"))).isEqualTo("\"plain\"");
    }
}
