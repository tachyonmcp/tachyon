/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.json;

import static dev.tachyonmcp.core.test.TestUtils.parseJson;
import static dev.tachyonmcp.core.test.TestUtils.properties;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.tachyonmcp.api.json.JsonDocument;
import dev.tachyonmcp.api.json.PayloadSerializer;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

class JsonUtilsTest {

    private static final PayloadSerializer SERDE = new JacksonPayloadSerde();

    @Test
    void mapperConvertsBareMillisecondsNumberToDuration() {
        var result = JsonUtils.mapper().convertValue(Map.of("ttl", 5000), WithTtl.class);

        assertThat(result.ttl()).isEqualTo(Duration.ofMillis(5000));
    }

    @Test
    void mapperConvertsAbsentTtlToNullDuration() {
        var result = JsonUtils.mapper().convertValue(Map.of(), WithTtl.class);

        assertThat(result.ttl()).isNull();
    }

    @Test
    void toObjectTreeBuildsTheSameTreesAsValueToTree() {
        var nested = new LinkedHashMap<String, Object>();
        nested.put("taskId", "t-1");
        nested.put("missing", null);
        nested.put("tags", new LinkedHashSet<>(List.of("a", "b")));
        var values = new LinkedHashMap<String, Object>();
        values.put("null", null);
        values.put("text", "req-42");
        values.put("flag", true);
        values.put("int", 7);
        values.put("long", 1L << 40);
        values.put("short", (short) 3);
        values.put("byte", (byte) 4);
        values.put("double", 1.5d);
        values.put("float", 2.5f);
        values.put("decimal", new BigDecimal("1.10"));
        values.put("bigint", new BigInteger("12345678901234567890"));
        values.put("bytes", new byte[] {1, 2});
        values.put("instant", Instant.EPOCH);
        values.put("list", Arrays.asList("a", null, 1));
        values.put("nested", nested);
        values.put("intKeys", Map.of(1, "one"));
        values.put("tree", JsonNodeFactory.instance.objectNode().put("k", 1));

        var result = JsonUtils.toObjectTree(values);

        assertThat(properties(result)).containsExactlyEntriesOf(expectedTrees(values));
        assertThat(result.get("tree")).isNotSameAs(values.get("tree"));
        assertThat(JsonUtils.toObjectTree(null)).isNull();
    }

    private static Map<String, JsonNode> expectedTrees(Map<String, Object> values) {
        var expected = new LinkedHashMap<String, JsonNode>();
        values.forEach((key, value) -> expected.put(key, JsonUtils.mapper().valueToTree(value)));
        return expected;
    }

    @Test
    void valueToObjectNodeNullReturnsNull() {
        assertThat(JsonUtils.valueToObjectNode(null, SERDE)).isNull();
    }

    @Test
    void valueToObjectNodeJsonDocumentObjectReturnsNode() {
        var result = JsonUtils.valueToObjectNode(JsonDocument.of("{\"key\":\"val\"}"), SERDE);
        assertThat(result).isNotNull();
        assertThat(result.get("key").asString()).isEqualTo("val");
    }

    @Test
    void valueToObjectNodeJsonDocumentNonObjectReturnsNull() {
        var result = JsonUtils.valueToObjectNode(JsonDocument.of("\"string\""), SERDE);
        assertThat(result).isNull();
    }

    @Test
    void parseReusesProviderRepresentation() {
        var node = parseJson("{\"key\":\"value\"}");
        var document = new RetainedDocument("{\"ignored\":true}", node);

        assertThat(JsonUtils.parse(document)).isSameAs(node);
    }

    @Test
    void valueToObjectNodeJsonNodeObjectReturnsNode() {
        var node = parseJson("{\"a\":1}");
        var result = JsonUtils.valueToObjectNode(node, SERDE);
        assertThat(result).isSameAs(node);
    }

    @Test
    void valueToObjectNodeJsonNodeNonObjectReturnsNull() {
        var result = JsonUtils.valueToObjectNode(parseJson("\"string\""), SERDE);
        assertThat(result).isNull();
    }

    @Test
    void valueToObjectNodeMapReturnsNode() {
        var result = JsonUtils.valueToObjectNode(Map.of("msg", "hello", "n", 42), SERDE);
        assertThat(result).isNotNull();
        assertThat(result.get("msg").asString()).isEqualTo("hello");
        assertThat(result.get("n").asInt()).isEqualTo(42);
    }

    @Test
    void valueToObjectNodeMapWithJsonNodeValuesReturnsNode() {
        var jsonVal = JsonNodeFactory.instance.stringNode("json-val");
        var result = JsonUtils.valueToObjectNode(Map.of("f1", jsonVal, "f2", "plain"), SERDE);
        assertThat(result).isNotNull();
        assertThat(result.get("f1").asString()).isEqualTo("json-val");
        assertThat(result.get("f2").asString()).isEqualTo("plain");
    }

    @Test
    void valueToObjectNodePojoReturnsNode() {
        var result = JsonUtils.valueToObjectNode(new SamplePojo("test", 42), SERDE);
        assertThat(result).isNotNull();
        assertThat(result.get("name").asString()).isEqualTo("test");
        assertThat(result.get("value").asInt()).isEqualTo(42);
    }

    @Test
    void valueToObjectNodeStringReturnsNull() {
        var result = JsonUtils.valueToObjectNode("just a string", SERDE);
        assertThat(result).isNull();
    }

    @Test
    void serializeStructuredWithJsonDocumentPassesThrough() {
        var result = ToolResult.structured(JsonDocument.of("{\"x\":1}"), "text");
        var serialized = JsonUtils.serializeStructured(result, SERDE);
        assertThat(serialized).isSameAs(result);
    }

    @Test
    void serializeStructuredWithJsonNodePassesThrough() {
        var sv = parseJson("{\"y\":2}");
        var result = ToolResult.structured(sv, "text");
        var serialized = JsonUtils.serializeStructured(result, SERDE);
        assertThat(serialized).isSameAs(result);
    }

    @Test
    void serializeStructuredWithNullStructuredValuePassesThrough() {
        var result = ToolResult.text("text only");
        var serialized = JsonUtils.serializeStructured(result, SERDE);
        assertThat(serialized).isSameAs(result);
    }

    @Test
    void serializeStructuredWithPojoWrapsInJsonDocument() {
        var result = ToolResult.structured(new SamplePojo("x", 1), "text");
        var serialized = JsonUtils.serializeStructured(result, SERDE);
        assertThat(serialized).isInstanceOf(ToolResult.Success.class);
        var sv = ((ToolResult.Success) serialized).structuredValue();
        assertThat(sv).isInstanceOf(JsonDocument.class);
        var parsed = parseJson(((JsonDocument) Objects.requireNonNull(sv)).json());
        assertThat(parsed.get("name").asString()).isEqualTo("x");
        assertThat(parsed.get("value").asInt()).isEqualTo(1);
    }

    @Test
    void serializeStructuredWithMapContainingJsonNodesUsesJackson() {
        var jsonVal = parseJson("{\"inner\":\"val\"}");
        var result = ToolResult.structured(Map.of("field", jsonVal), "text");
        var serialized = JsonUtils.serializeStructured(result, SERDE);
        assertThat(serialized).isInstanceOf(ToolResult.Success.class);
        var sv = ((ToolResult.Success) serialized).structuredValue();
        assertThat(sv).isInstanceOf(JsonDocument.class);
    }

    @Test
    void serializeStructuredPreservesMeta() {
        var inner = ToolResult.structured(new SamplePojo("m", 2), "text");
        var withMeta = inner.withMeta("k", "v");
        var serialized = JsonUtils.serializeStructured(withMeta, SERDE);
        assertThat(serialized).isInstanceOf(ToolResult.Success.class);
        var success = (ToolResult.Success) serialized;
        assertThat(success.structuredValue()).isInstanceOf(JsonDocument.class);
        assertThat(success.meta()).containsEntry("k", "v");
    }

    public record SamplePojo(String name, int value) {}

    private record WithTtl(@JsonProperty("ttl") Duration ttl) {}

    private record RetainedDocument(String json, JsonNode node) implements JsonDocument {
        @Override
        public <T> Optional<T> unwrap(Class<T> type) {
            return type.isInstance(node) ? Optional.of(type.cast(node)) : Optional.empty();
        }
    }
}
