/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.protocol.mcp.v2025_11_25.codecs;

import dev.tachyonmcp.core.protocol.codec.CodecSupport;
import tools.jackson.core.JsonToken;

/** Decoding helpers built on {@link CodecRegistry}. */
public final class ProtocolCodecUtil {

    private ProtocolCodecUtil() {}

    /**
     * Decodes a JSON object string using the registered codec for the given type.
     *
     * @param json       the JSON object to decode
     * @param targetType the model type to decode into
     * @param <T>        the model type
     * @return the decoded instance
     * @throws IllegalArgumentException if {@code json} is not a JSON object
     * @throws tools.jackson.core.JacksonException if {@code json} is malformed
     */
    public static <T> T decodeWithCodec(String json, Class<T> targetType) {
        var codec = CodecRegistry.codecFor(targetType);
        try (var p = CodecSupport.createParser(json)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalArgumentException("Expected JSON object for " + targetType.getSimpleName());
            }
            return codec.decode(p);
        }
    }
}
