/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.protocol.codec;

import dev.tachyonmcp.api.annotations.InternalApi;
import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.Base64Variants;
import tools.jackson.core.JsonEncoding;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.util.RawValue;
import tools.jackson.databind.util.TokenBuffer;

/**
 * Streaming JSON primitives for generated codecs and the JSON-RPC layer: one {@link JsonFactory},
 * no {@code ObjectMapper}. Trees are read and written token by token.
 */
@InternalApi
public final class CodecSupport {

    /** The shared streaming factory. */
    public static final JsonFactory FACTORY = new JsonFactory();

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private CodecSupport() {}

    /** Writes JSON into a generator. */
    @FunctionalInterface
    public interface JsonWriter {
        /**
         * Writes one JSON value.
         *
         * @param gen the generator to write to
         */
        void write(JsonGenerator gen);
    }

    /**
     * Creates a parser over UTF-8 JSON bytes.
     *
     * @param data the JSON bytes
     * @return the parser
     */
    public static JsonParser createParser(byte[] data) {
        return FACTORY.createParser(ObjectReadContext.empty(), data);
    }

    /**
     * Creates a parser over a JSON string.
     *
     * @param json the JSON text
     * @return the parser
     */
    public static JsonParser createParser(String json) {
        return FACTORY.createParser(ObjectReadContext.empty(), json);
    }

    /**
     * Serializes whatever {@code writer} emits to UTF-8 JSON bytes.
     *
     * @param writer the writer
     * @return the JSON bytes
     */
    public static byte[] writeToBytes(JsonWriter writer) {
        var out = new ByteArrayOutputStream(256);
        try (var gen = FACTORY.createGenerator(ObjectWriteContext.empty(), out, JsonEncoding.UTF8)) {
            writer.write(gen);
        }
        return out.toByteArray();
    }

    /**
     * Reads the current value as a tree, keeping each integer's width (int, long, big integer)
     * as Jackson's own tree reader does; decimals become doubles.
     *
     * @param parser the parser positioned at a value
     * @return the tree
     */
    public static JsonNode readTree(JsonParser parser) {
        return readTree(parser, false);
    }

    /**
     * Reads the current value of JSON-RPC wire text as a tree, widening integers that fit a long
     * to long nodes, so wire values such as numeric request ids and {@code _meta} entries are
     * always {@code Long}; larger integers become big-integer nodes.
     *
     * @param parser the parser positioned at a value
     * @return the tree
     */
    public static JsonNode readWireTree(JsonParser parser) {
        return readTree(parser, true);
    }

    private static JsonNode readTree(JsonParser parser, boolean widenIntegers) {
        return switch (parser.currentToken()) {
            case START_OBJECT -> readObjectNode(parser, widenIntegers);
            case START_ARRAY -> {
                var array = NODES.arrayNode();
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    array.add(readTree(parser, widenIntegers));
                }
                yield array;
            }
            case VALUE_STRING -> NODES.stringNode(parser.getString());
            case VALUE_NUMBER_INT ->
                switch (parser.getNumberType()) {
                    case INT ->
                        widenIntegers
                                ? NODES.numberNode(parser.getLongValue())
                                : NODES.numberNode(parser.getIntValue());
                    case LONG -> NODES.numberNode(parser.getLongValue());
                    default -> NODES.numberNode(parser.getBigIntegerValue());
                };
            case VALUE_NUMBER_FLOAT -> NODES.numberNode(parser.getDoubleValue());
            case VALUE_TRUE -> NODES.booleanNode(true);
            case VALUE_FALSE -> NODES.booleanNode(false);
            case VALUE_NULL -> NODES.nullNode();
            case VALUE_EMBEDDED_OBJECT -> readEmbedded(parser, widenIntegers);
            default ->
                throw MismatchedInputException.from(
                        parser, JsonNode.class, "Unexpected token " + parser.currentToken());
        };
    }

    /**
     * Encodes {@code value} straight into a tree through a {@link TokenBuffer}, as
     * {@link #readWireTree} would read its JSON text: integers that fit a long become longs,
     * binary values become base64 string nodes, and raw values are parsed.
     *
     * @param codec the value's codec
     * @param value the value
     * @param <T> the value type
     * @return the tree
     */
    public static <T> JsonNode encodeToTree(Codec<T> codec, T value) {
        try (var buffer = TokenBuffer.forGeneration()) {
            codec.encode(buffer, value);
            try (var parser = buffer.asParser()) {
                parser.nextToken();
                return readWireTree(parser);
            }
        }
    }

    /** A {@link TokenBuffer} keeps binary and raw values as objects; read them as their JSON text would be. */
    private static JsonNode readEmbedded(JsonParser parser, boolean widenIntegers) {
        return switch (parser.getEmbeddedObject()) {
            case byte[] bytes ->
                NODES.stringNode(Base64Variants.getDefaultVariant().encode(bytes));
            case RawValue raw -> {
                try (var rawParser = createParser(String.valueOf(raw.rawValue()))) {
                    rawParser.nextToken();
                    yield readTree(rawParser, widenIntegers);
                }
            }
            case null, default ->
                throw MismatchedInputException.from(parser, JsonNode.class, "Unexpected embedded value");
        };
    }

    /**
     * Reads the current value as a tree, or {@code null} for a JSON {@code null}.
     *
     * @param parser the parser positioned at a value
     * @return the tree, or {@code null}
     */
    public static @Nullable JsonNode readNullableTree(JsonParser parser) {
        return parser.currentToken() == JsonToken.VALUE_NULL ? null : readTree(parser);
    }

    /**
     * Reads the current value as an object, or {@code null} for a JSON {@code null}.
     *
     * @param parser the parser positioned at a value
     * @param field the property name, for the error message
     * @return the object, or {@code null}
     */
    public static @Nullable ObjectNode readObject(JsonParser parser, String field) {
        return switch (parser.currentToken()) {
            case VALUE_NULL -> null;
            case START_OBJECT -> readObjectNode(parser, false);
            default -> throw wrongType(parser, field, "an object");
        };
    }

    private static ObjectNode readObjectNode(JsonParser parser, boolean widenIntegers) {
        var node = NODES.objectNode();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            var name = parser.currentName();
            parser.nextToken();
            node.set(name, readTree(parser, widenIntegers));
        }
        return node;
    }

    /**
     * Writes a tree token by token, or JSON {@code null} for a {@code null} node.
     *
     * @param gen the generator
     * @param node the tree
     */
    public static void writeTree(JsonGenerator gen, @Nullable JsonNode node) {
        if (node == null) {
            gen.writeNull();
            return;
        }
        try (var parser = node.traverse(ObjectReadContext.empty())) {
            parser.nextToken();
            gen.copyCurrentStructure(parser);
        }
    }

    /**
     * Reads the current value into {@code properties} under {@code name}, creating the object on
     * first use; for the properties a model keeps beyond its declared fields.
     *
     * @param properties the properties read so far, or {@code null}
     * @param name the property name
     * @param parser the parser positioned at the property's value
     * @return {@code properties}, or a new object holding the one property
     */
    public static ObjectNode readProperty(@Nullable ObjectNode properties, String name, JsonParser parser) {
        var target = properties != null ? properties : NODES.objectNode();
        target.set(name, readTree(parser));
        return target;
    }

    /**
     * Writes each property of {@code properties} into the object currently open in {@code gen}.
     *
     * @param gen the generator
     * @param properties the properties
     */
    public static void writeProperties(JsonGenerator gen, ObjectNode properties) {
        for (var property : properties.properties()) {
            gen.writeName(property.getKey());
            writeTree(gen, property.getValue());
        }
    }

    /**
     * Reports a property whose JSON type does not match the schema. Codecs skip properties they do
     * not model, but a modeled property with the wrong type is a client error.
     *
     * @param parser the parser positioned at the offending value
     * @param field the property name
     * @param expected the expected shape, e.g. {@code "an object"}
     * @return the exception to throw
     */
    public static MismatchedInputException wrongType(JsonParser parser, String field, String expected) {
        return MismatchedInputException.from(parser, Object.class, "Property '" + field + "' expects " + expected);
    }

    /**
     * Copies the current value, verbatim, into a JSON string.
     *
     * @param parser the parser positioned at a value
     * @return the raw JSON, or {@code null} for a JSON {@code null}
     */
    public static @Nullable String readRawJson(JsonParser parser) {
        if (parser.currentToken() == JsonToken.VALUE_NULL) return null;
        var writer = new StringWriter();
        try (var gen = FACTORY.createGenerator(ObjectWriteContext.empty(), writer)) {
            gen.copyCurrentStructure(parser);
        }
        return writer.toString();
    }

    /**
     * Reads a string, tolerating an explicit JSON null.
     *
     * @param parser the parser positioned at a value
     * @return the string, or {@code null}
     */
    public static @Nullable String decodeString(JsonParser parser) {
        return parser.currentToken() == JsonToken.VALUE_NULL ? null : parser.getString();
    }

    /**
     * Reads a boolean, tolerating an explicit JSON null.
     *
     * @param parser the parser positioned at a value
     * @return the boolean, or {@code null}
     */
    public static @Nullable Boolean decodeBoolean(JsonParser parser) {
        return parser.currentToken() == JsonToken.VALUE_NULL ? null : parser.getBooleanValue();
    }

    /**
     * Reads a long, tolerating an explicit JSON null.
     *
     * @param parser the parser positioned at a value
     * @return the long, or {@code null}
     */
    public static @Nullable Long decodeLong(JsonParser parser) {
        return parser.currentToken() == JsonToken.VALUE_NULL ? null : parser.getLongValue();
    }

    /**
     * Reads a double, tolerating an explicit JSON null.
     *
     * @param parser the parser positioned at a value
     * @return the double, or {@code null}
     */
    public static @Nullable Double decodeDouble(JsonParser parser) {
        return parser.currentToken() == JsonToken.VALUE_NULL ? null : parser.getDoubleValue();
    }

    /**
     * Reads a boolean, using {@code fallback} for an explicit JSON null.
     *
     * @param parser the parser positioned at a value
     * @param fallback the value for JSON null
     * @return the boolean
     */
    public static boolean decodeBoolean(JsonParser parser, boolean fallback) {
        return parser.currentToken() == JsonToken.VALUE_NULL ? fallback : parser.getBooleanValue();
    }

    /**
     * Reads a long, using {@code fallback} for an explicit JSON null.
     *
     * @param parser the parser positioned at a value
     * @param fallback the value for JSON null
     * @return the long
     */
    public static long decodeLong(JsonParser parser, long fallback) {
        return parser.currentToken() == JsonToken.VALUE_NULL ? fallback : parser.getLongValue();
    }

    /**
     * Reads a double, using {@code fallback} for an explicit JSON null.
     *
     * @param parser the parser positioned at a value
     * @param fallback the value for JSON null
     * @return the double
     */
    public static double decodeDouble(JsonParser parser, double fallback) {
        return parser.currentToken() == JsonToken.VALUE_NULL ? fallback : parser.getDoubleValue();
    }

    /**
     * Reads base64 binary, tolerating an explicit JSON null.
     *
     * @param parser the parser positioned at a value
     * @return the bytes, or {@code null}
     */
    public static byte @Nullable [] decodeBinary(JsonParser parser) {
        return parser.currentToken() == JsonToken.VALUE_NULL ? null : parser.getBinaryValue();
    }

    /**
     * Writes a value of a type the generated codecs emit generically: JSON {@code null}, a string,
     * boolean, long, double, or tree.
     *
     * @param gen the generator
     * @param value the value
     * @throws IllegalArgumentException for any other type
     */
    public static void encodeValue(JsonGenerator gen, @Nullable Object value) {
        switch (value) {
            case null -> gen.writeNull();
            case String s -> gen.writeString(s);
            case Boolean b -> gen.writeBoolean(b);
            case Long l -> gen.writeNumber(l);
            case Double d -> gen.writeNumber(d);
            case JsonNode n -> writeTree(gen, n);
            default ->
                throw new IllegalArgumentException(
                        "No codec for " + value.getClass().getName());
        }
    }
}
