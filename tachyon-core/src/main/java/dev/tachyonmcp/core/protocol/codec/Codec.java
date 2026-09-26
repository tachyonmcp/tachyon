/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.protocol.codec;

import dev.tachyonmcp.api.annotations.InternalApi;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.util.TokenBuffer;

/**
 * Streaming codec for one generated protocol model, shared by every protocol version and by
 * extension codecs.
 *
 * @param <T> the model type
 */
@InternalApi
public interface Codec<T> {

    /**
     * Deserializes a value from JSON.
     *
     * @param parser the JSON parser positioned at a value
     * @return the decoded value
     * @throws JacksonException on parse failure
     */
    T decode(JsonParser parser);

    /**
     * Serializes a value to JSON.
     *
     * @param gen the JSON generator to write to
     * @param value the value to serialize
     * @throws JacksonException on write failure
     */
    void encode(JsonGenerator gen, T value);

    /**
     * Writes the value's properties into the object currently open in {@code gen}, e.g. to inline
     * a union variant next to its container's own properties. Generated codecs write them
     * directly; this default buffers {@link #encode} and copies the properties.
     *
     * @param gen the JSON generator, inside an open object
     * @param value the value to serialize
     * @throws JacksonException on write failure
     */
    default void encodeProperties(JsonGenerator gen, T value) {
        try (var buffer = TokenBuffer.forGeneration()) {
            encode(buffer, value);
            try (var parser = buffer.asParser()) {
                parser.nextToken();
                while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                    gen.copyCurrentStructure(parser);
                }
            }
        }
    }

    /**
     * Serializes a value to a UTF-8 JSON byte array.
     *
     * @param value the value to serialize
     * @return the UTF-8 encoded JSON
     * @throws JacksonException on write failure
     */
    default byte[] encodeToBytes(T value) {
        return CodecSupport.writeToBytes(gen -> encode(gen, value));
    }

    /**
     * Deserializes a value from a UTF-8 JSON byte array.
     *
     * @param data the UTF-8 encoded JSON object
     * @return the decoded value
     * @throws JacksonException on parse failure, or when {@code data} is not a JSON object
     */
    default T decodeFromBytes(byte[] data) {
        try (var parser = CodecSupport.createParser(data)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw MismatchedInputException.from(parser, Object.class, "Expected JSON object");
            }
            return decode(parser);
        }
    }
}
