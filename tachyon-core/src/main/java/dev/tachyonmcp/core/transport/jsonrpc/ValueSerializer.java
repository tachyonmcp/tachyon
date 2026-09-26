/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.jsonrpc;

import dev.tachyonmcp.core.protocol.codec.CodecSupport;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JsonEncoding;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.databind.JsonNode;

public final class ValueSerializer {

    private ValueSerializer() {}

    public static @Nullable String writeValueAsString(@Nullable Object value) {
        try (var out = new ByteArrayOutputStream(256);
                var gen = CodecSupport.FACTORY.createGenerator(ObjectWriteContext.empty(), out, JsonEncoding.UTF8)) {
            writeJsonValue(gen, value);
            gen.flush();
            return out.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void writeJsonValue(JsonGenerator gen, @Nullable Object value) {
        switch (value) {
            case null -> gen.writeNull();
            case JsonNode node -> CodecSupport.writeTree(gen, node);
            case Map<?, ?> map -> {
                gen.writeStartObject();
                for (var entry : map.entrySet()) {
                    gen.writeName(entry.getKey().toString());
                    writeJsonValue(gen, entry.getValue());
                }
                gen.writeEndObject();
            }
            case List<?> list -> {
                gen.writeStartArray();
                for (var item : list) {
                    writeJsonValue(gen, item);
                }
                gen.writeEndArray();
            }
            case String s -> gen.writeString(s);
            case Long l -> gen.writeNumber(l);
            case Integer i -> gen.writeNumber(i);
            case Double d -> gen.writeNumber(d);
            case Float f -> gen.writeNumber(f);
            case Boolean b -> gen.writeBoolean(b);
            default -> {
                if (!GeneratedCodecs.encode(gen, value)) {
                    gen.writeString(value.toString());
                }
            }
        }
    }
}
