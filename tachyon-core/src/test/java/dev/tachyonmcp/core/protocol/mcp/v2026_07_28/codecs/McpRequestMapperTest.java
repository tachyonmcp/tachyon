/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.protocol.mcp.v2026_07_28.codecs;

import static dev.tachyonmcp.core.test.TestUtils.parseJson;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.json.PayloadDeserializer;
import java.lang.reflect.Type;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpRequestMapperTest {

    private static final PayloadDeserializer NOOP_DESERIALIZER = new PayloadDeserializer() {
        @Override
        public <T> T deserialize(String json, Type targetType) {
            throw new UnsupportedOperationException("not used by these tests");
        }
    };

    @Test
    void ignoresLegacyTaskAugmentation() {
        var mapper = new McpRequestMapper();

        var result = mapper.callTool(Map.of("name", "greet", "task", "ignored"), NOOP_DESERIALIZER);

        assertThat(mapper.supportsLegacyTaskAugmentation()).isFalse();
        assertThat(result.request().name()).isEqualTo("greet");
        assertThat(result.taskAugmented()).isFalse();
        assertThat(result.taskTtl()).isNull();
    }

    @Test
    void versionSpecificParamsDecodeThroughOwnRegistry() {
        final var mapper = new McpRequestMapper();

        final var empty = mapper.subscriptionsListen(parseJson("{}"));
        assertThat(empty.toolsListChanged()).isFalse();
        assertThat(empty.resourceSubscriptions()).isEmpty();

        final var filtered = mapper.subscriptionsListen(parseJson("""
            {
              "notifications": {
                "toolsListChanged": true,
                "resourceSubscriptions": ["file:///a"]
              }
            }
            """));
        assertThat(filtered.toolsListChanged()).isTrue();
        assertThat(filtered.resourceSubscriptions()).containsExactly("file:///a");
    }

    @Test
    void declaredExtensionsMapWireMetadata() {
        final var extensions = new McpRequestMapper().declaredExtensions(parseJson("""
            {
              "_meta": {
                "io.modelcontextprotocol/clientCapabilities": {
                  "extensions": {
                    "com.example/feature": {"enabled": true, "limit": 7}
                  }
                }
              }
            }
            """));

        assertThat(extensions).containsOnlyKeys("com.example/feature");
        // language=json
        assertThatJson(extensions.get("com.example/feature").json()).isEqualTo("""
            {"enabled":true,"limit":7}
            """);
    }
}
