/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.json;

import static dev.tachyonmcp.core.transport.jsonrpc.JsonRpcCodec.readTreeValue;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.json.JsonDocument;
import dev.tachyonmcp.api.json.PayloadSerializer;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.transport.jsonrpc.JsonRpcCodec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.TreeNode;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.deser.std.StdDeserializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@InternalApi
public final class JsonUtils {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .addModule(new SimpleModule().addDeserializer(Duration.class, new MillisDurationDeserializer()))
            .build();
    public static final JsonFactory FACTORY = new JsonFactory();

    public static final ObjectReadContext TREE_READ_CONTEXT = new ObjectReadContext.Base() {
        @Override
        @SuppressWarnings("unchecked")
        public <T extends TreeNode> T readTree(JsonParser p) {
            try {
                return (T) readTreeValue(p);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    };

    private JsonUtils() {}

    /**
     * The shared Jackson mapper used for converting between raw JSON-ish values (maps, lists,
     * scalars, trees) and generated protocol model records. Deserializes {@link Duration} from a
     * bare wire number as milliseconds, matching the generated {@code Codec} classes' semantics
     * (e.g. {@code TaskMetadata.ttl}) rather than jackson-databind's default (seconds).
     */
    public static JsonMapper mapper() {
        return MAPPER;
    }

    /** Deserializes a bare JSON number as milliseconds, matching the generated {@code Codec} classes. */
    private static final class MillisDurationDeserializer extends StdDeserializer<Duration> {
        MillisDurationDeserializer() {
            super(Duration.class);
        }

        @Override
        public @Nullable Duration deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
            return p.currentToken() == JsonToken.VALUE_NULL ? null : Duration.ofMillis(p.getLongValue());
        }
    }

    public static JsonNode parse(String json) {
        return MAPPER.readTree(json);
    }

    public static JsonNode parse(JsonDocument document) {
        return document.unwrap(JsonNode.class).orElseGet(() -> parse(document.json()));
    }

    public static String writeString(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    public static @Nullable Map<String, JsonNode> toJsonNodeMap(@Nullable Map<String, ?> values) {
        if (values == null) return null;
        var result = new LinkedHashMap<String, JsonNode>(values.size());
        values.forEach((key, value) -> result.put(key, MAPPER.valueToTree(value)));
        return result;
    }

    public static @Nullable Map<String, Object> toObjectMap(@Nullable Map<String, JsonNode> values) {
        if (values == null) return null;
        var result = new LinkedHashMap<String, Object>(values.size());
        values.forEach((key, value) -> result.put(key, MAPPER.treeToValue(value, Object.class)));
        return result;
    }

    /**
     * Converts an object node to the {@code Map<String, Object>} shape the domain request types
     * take, mapping each property to a plain Java value.
     *
     * @param node the object node, or {@code null}
     * @return the property map, or {@code null} when {@code node} is {@code null}
     */
    public static @Nullable Map<String, Object> toObjectMap(@Nullable JsonNode node) {
        if (node == null) return null;
        var result = new LinkedHashMap<String, Object>(node.size());
        node.properties()
                .forEach(entry -> result.put(entry.getKey(), MAPPER.treeToValue(entry.getValue(), Object.class)));
        return result;
    }

    /**
     * Builds an object {@link JsonNode} from an ordered field map, preserving field order and
     * explicit {@code null} values (mapped to JSON {@code null} rather than omitted). Nested
     * {@link Map} and {@link List} values are converted recursively; other values are serialized
     * with the JSON-RPC serializer. Use it to emit hand-shaped wire objects for protocol versions
     * that lack generated codecs.
     */
    public static ObjectNode toObjectNode(Map<String, ?> fields) {
        var node = JsonNodeFactory.instance.objectNode();
        fields.forEach((key, value) -> node.set(key, toValueNode(value)));
        return node;
    }

    private static JsonNode toValueNode(@Nullable Object value) {
        switch (value) {
            case null -> {
                return JsonNodeFactory.instance.nullNode();
            }
            case JsonNode node -> {
                return node;
            }
            case String text -> {
                return JsonNodeFactory.instance.stringNode(text);
            }
            case Boolean flag -> {
                return JsonNodeFactory.instance.booleanNode(flag);
            }
            // Width-preserving on purpose: a scalar routed through the string round-trip below comes
            // back widened (every integral becomes a long), so an in-memory Integer params value
            // would not survive a Map -> tree -> Map trip as an Integer.
            case Integer i -> {
                return JsonNodeFactory.instance.numberNode(i);
            }
            case Long l -> {
                return JsonNodeFactory.instance.numberNode(l);
            }
            case Short sh -> {
                return JsonNodeFactory.instance.numberNode(sh);
            }
            case Byte b -> {
                return JsonNodeFactory.instance.numberNode(b);
            }
            case Double d -> {
                return JsonNodeFactory.instance.numberNode(d);
            }
            case Float f -> {
                return JsonNodeFactory.instance.numberNode(f);
            }
            case BigDecimal d -> {
                return JsonNodeFactory.instance.numberNode(d);
            }
            case BigInteger i -> {
                return JsonNodeFactory.instance.numberNode(i);
            }
            case Map<?, ?> map -> {
                var node = JsonNodeFactory.instance.objectNode();
                map.forEach((key, val) -> {
                    if (key instanceof String text) node.set(text, toValueNode(val));
                });
                return node;
            }
            case List<?> list -> {
                var array = JsonNodeFactory.instance.arrayNode();
                list.forEach(item -> array.add(toValueNode(item)));
                return array;
            }
            default -> {}
        }
        return parseJsonNode(JsonRpcCodec.writeValueAsString(value));
    }

    public static JsonNode parseJsonNode(String json) {
        try (var p = FACTORY.createParser(TREE_READ_CONTEXT, json)) {
            p.nextToken();
            return readTreeValue(p);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse JSON", e);
        }
    }

    /**
     * Converts a given value into a {@link JsonNode} representing an object, if applicable.
     * The method supports the following input types:
     * - {@link JsonDocument}, where the contained JSON string is parsed into a {@link JsonNode}.
     * - {@link JsonNode}, if it is already an object node.
     * - {@link java.util.Map}, where its entries are transformed into an object node.
     * - Other non-null values: serialized via the provided {@link PayloadSerializer}.
     *
     * @param <T>        the type of the value to be converted
     * @param value      the value to be converted
     * @param serializer the serializer for non-tree values
     * @return a {@link JsonNode} object node representation of the value, or {@code null} if the value cannot be converted
     */
    public static @Nullable <T> JsonNode valueToObjectNode(@Nullable T value, PayloadSerializer serializer) {
        if (value instanceof JsonDocument document) {
            var node = JsonUtils.parse(document);
            return node.isObject() ? node : null;
        }
        if (value instanceof JsonNode node) {
            return node.isObject() ? node : null;
        }
        if (value instanceof Map<?, ?> map) {
            var contentNode = JsonNodeFactory.instance.objectNode();
            for (var entry : map.entrySet()) {
                if (entry.getKey() instanceof String k) {
                    var v = entry.getValue();
                    if (v instanceof JsonNode jn) {
                        contentNode.set(k, jn);
                    } else if (v != null) {
                        contentNode.set(k, JsonUtils.parseJsonNode(JsonRpcCodec.writeValueAsString(v)));
                    }
                }
            }
            return contentNode;
        }
        if (value != null) {
            var json = serializer.serialize(value);
            var node = MAPPER.readTree(json);
            return node.isObject() ? node : null;
        }
        return null;
    }

    /**
     * Serializes non-tree structured values in a {@link ToolResult} into {@link JsonDocument}.
     * {@link JsonNode} and {@link JsonDocument} pass through. Maps carrying {@link JsonNode} values
     * are serialized with Jackson regardless of the configured serializer — a non-Jackson serde
     * cannot encode Jackson trees.
     */
    public static ToolResult serializeStructured(ToolResult result, PayloadSerializer serializer) {
        if (!(result instanceof ToolResult.Success success)) return result;
        var sv = success.structuredValue();
        if (sv == null || sv instanceof JsonDocument || sv instanceof JsonNode) return result;
        var json = containsJsonNodes(sv) ? MAPPER.writeValueAsString(sv) : serializer.serialize(sv);
        return ToolResult.Success.builder()
                .structuredValue(JsonDocument.of(json))
                .content(success.content())
                .meta(success.meta())
                .build();
    }

    private static boolean containsJsonNodes(Object structuredValue) {
        return structuredValue instanceof Map<?, ?> map && map.values().stream().anyMatch(v -> v instanceof JsonNode);
    }
}
