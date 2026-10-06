/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.jsonrpc;

import static dev.tachyonmcp.core.protocol.codec.CodecSupport.FACTORY;

import dev.tachyonmcp.api.server.domain.RequestId;
import dev.tachyonmcp.core.protocol.codec.CodecSupport;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JsonEncoding;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.ObjectWriteContext;

/** Low-level JSON-RPC 2.0 codec: parse and serialize messages to/from Netty {@link ByteBuf}. */
public final class JsonRpcCodec {

    private static final String JSONRPC = "jsonrpc";
    private static final String JSONRPC_VERSION = "2.0";
    private static final String ID = "id";
    private static final String METHOD = "method";
    private static final String PARAMS = "params";
    private static final String RESULT = "result";
    private static final String ERROR = "error";
    private static final String CODE = "code";
    private static final String MESSAGE = "message";
    private static final String DATA = "data";

    private static final int F_JSONRPC = 1;
    private static final int F_ID = 1 << 1;
    private static final int F_METHOD = 1 << 2;
    private static final int F_PARAMS = 1 << 3;
    private static final int F_RESULT = 1 << 4;
    private static final int F_ERROR = 1 << 5;
    private static final int F_KIND = F_METHOD | F_RESULT | F_ERROR;

    private JsonRpcCodec() {}

    /**
     * Parses exactly one JSON-RPC 2.0 message from a {@link ByteBuf}: {@code "jsonrpc":"2.0"}
     * required, exactly one of {@code method}/{@code result}/{@code error}, no duplicate envelope
     * fields, a non-null {@code id}, and nothing but whitespace after the root object.
     *
     * @param buf the buffer to parse
     * @return the parsed message
     * @throws IllegalArgumentException when the body is valid JSON but not a JSON-RPC envelope
     * @throws RuntimeException when the body is not a single valid JSON value
     */
    public static JsonRpcMessage parseRequest(ByteBuf buf) {
        try (var in = new ByteBufInputStream(buf);
                JsonParser p = FACTORY.createParser(ObjectReadContext.empty(), (InputStream) in)) {
            return parseRootObject(p);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse JSON-RPC message", e);
        }
    }

    /**
     * The outcome of parsing a request body, with the two failure kinds JSON-RPC answers
     * differently kept apart instead of collapsed into one exception.
     *
     * @param message the parsed message, or {@code null} when the body yielded none
     * @param invalidRequest {@code true} when {@code message} is {@code null} because the body was
     *     syntactically valid JSON that is not a JSON-RPC envelope ({@code -32600} territory), as
     *     opposed to a JSON syntax failure ({@code -32700} territory)
     */
    public record Parse(@Nullable JsonRpcMessage message, boolean invalidRequest) {}

    /**
     * Parses a JSON-RPC message from a {@link ByteBuf}, classifying a failure rather than throwing
     * it. The single place that decides which of {@code -32700} and {@code -32600} a malformed body
     * earns, so every dispatch path answers the same body the same way.
     *
     * @param buf the buffer to parse
     * @return the parse outcome
     */
    public static Parse tryParseRequest(ByteBuf buf) {
        try {
            return new Parse(parseRequest(buf), false);
        } catch (IllegalArgumentException e) {
            return new Parse(null, true);
        } catch (RuntimeException e) {
            return new Parse(null, false);
        }
    }

    /** Serializes a JSON-RPC response. */
    public static byte[] serializeResponse(@Nullable RequestId id, @Nullable String resultJson) {
        return serialize(gen -> {
            gen.writeStartObject();
            gen.writeStringProperty(JSONRPC, JSONRPC_VERSION);
            writeId(gen, id);
            if (resultJson != null) {
                gen.writeName(RESULT);
                gen.writeRawValue(resultJson);
            } else {
                gen.writeNullProperty(RESULT);
            }
            gen.writeEndObject();
        });
    }

    /** Serializes a JSON-RPC error response. */
    public static byte[] serializeError(@Nullable RequestId id, int code, String message, @Nullable String dataJson) {
        return serialize(gen -> {
            gen.writeStartObject();
            gen.writeStringProperty(JSONRPC, JSONRPC_VERSION);
            writeId(gen, id);
            gen.writeObjectPropertyStart(ERROR);
            gen.writeNumberProperty(CODE, code);
            gen.writeStringProperty(MESSAGE, message);
            if (dataJson != null) {
                gen.writeName(DATA);
                gen.writeRawValue(dataJson);
            }
            gen.writeEndObject();
            gen.writeEndObject();
        });
    }

    private static void writeId(JsonGenerator gen, @Nullable RequestId id) {
        gen.writeName(ID);
        switch (id) {
            case null -> gen.writeNull();
            case RequestId.StringValue(var v) -> gen.writeString(v);
            case RequestId.NumericValue(var v) -> {
                switch (v) {
                    case Long l -> gen.writeNumber(l);
                    case Integer i -> gen.writeNumber(i);
                    default -> gen.writeNumber(v.doubleValue());
                }
            }
        }
    }

    /** Serializes a JSON-RPC notification to a string. */
    public static String serializeNotificationAsString(String method, String paramsJson) {
        return serializeToString(gen -> {
            gen.writeStartObject();
            gen.writeStringProperty(JSONRPC, JSONRPC_VERSION);
            gen.writeStringProperty(METHOD, method);
            gen.writeName(PARAMS);
            gen.writeRawValue(paramsJson);
            gen.writeEndObject();
        });
    }

    /** Serializes a JSON-RPC request to a string. */
    public static String serializeRequestAsString(RequestId id, String method, String paramsJson) {
        return serializeToString(gen -> {
            gen.writeStartObject();
            gen.writeStringProperty(JSONRPC, JSONRPC_VERSION);
            gen.writeStringProperty(METHOD, method);
            writeId(gen, id);
            gen.writeName(PARAMS);
            gen.writeRawValue(paramsJson);
            gen.writeEndObject();
        });
    }

    private static String serializeToString(JsonWriter writer) {
        var sw = new StringWriter(256);
        try (JsonGenerator gen = FACTORY.createGenerator(ObjectWriteContext.empty(), sw)) {
            writer.write(gen);
            gen.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to serialize JSON", e);
        }
        return sw.toString();
    }

    private static JsonRpcMessage parseRootObject(JsonParser p) throws IOException {
        var first = p.nextToken();
        if (first == null) {
            // An empty or whitespace-only body never produced JSON at all: a syntax failure, not an
            // envelope that happens to be wrong.
            throw new IOException("Empty JSON-RPC message");
        }
        if (first != JsonToken.START_OBJECT) {
            // Consume the value before rejecting it: only that tells a complete array or scalar
            // (valid JSON, wrong envelope) apart from a truncated one (a syntax failure, which
            // surfaces here as the parser's own unchecked read exception).
            p.skipChildren();
            throw new IllegalArgumentException("Expected JSON object");
        }
        var message = parseMessage(p);
        if (p.nextToken() != null) {
            throw new IOException("Trailing content after JSON-RPC message");
        }
        return message;
    }

    /**
     * Reads one envelope, throwing {@link IllegalArgumentException} at the first violation so the
     * rest of an already-invalid message is never parsed.
     */
    private static JsonRpcMessage parseMessage(JsonParser p) throws IOException {
        RequestId id = null;
        String method = null;
        Object params = null;
        String resultJson = null;
        JsonRpcMessage.Error error = null;
        int seen = 0;

        while (p.nextToken() != JsonToken.END_OBJECT) {
            final var field = p.currentName();
            final var token = p.nextToken();
            switch (field) {
                case JSONRPC -> {
                    seen = mark(seen, F_JSONRPC, field);
                    if (token != JsonToken.VALUE_STRING || !JSONRPC_VERSION.equals(p.getString())) {
                        throw new IllegalArgumentException("Unsupported JSON-RPC version");
                    }
                }
                case ID -> {
                    seen = mark(seen, F_ID, field);
                    id = parseId(p);
                }
                case METHOD -> {
                    seen = mark(seen, F_METHOD, field);
                    method = requireString(p, field);
                }
                case PARAMS -> {
                    seen = mark(seen, F_PARAMS, field);
                    params = switch (token) {
                        case VALUE_NULL -> null;
                        case START_OBJECT, START_ARRAY -> CodecSupport.readWireTree(p);
                        default -> throw new IllegalArgumentException("params must be an object or array");
                    };
                }
                case RESULT -> {
                    seen = mark(seen, F_RESULT, field);
                    resultJson = readRawJson(p);
                }
                case ERROR -> {
                    seen = mark(seen, F_ERROR, field);
                    error = parseError(p);
                }
                default -> p.skipChildren();
            }
        }

        if ((seen & F_JSONRPC) == 0) {
            throw new IllegalArgumentException("Missing JSON-RPC version");
        }
        if (method != null) {
            return id != null
                    ? new JsonRpcMessage.Request<>(id, method, params)
                    : new JsonRpcMessage.Notification<>(method, params);
        }
        if (resultJson != null) {
            if (id == null) {
                throw new IllegalArgumentException("JSON-RPC response requires an id");
            }
            return new JsonRpcMessage.Response(id, resultJson);
        }
        if (error != null) {
            return id == null ? error : new JsonRpcMessage.Error(id, error.code(), error.message(), error.dataJson());
        }
        throw new IllegalArgumentException("Invalid JSON-RPC message: no method, result, or error");
    }

    private static int mark(int seen, int bit, String field) {
        if ((seen & bit) != 0 || ((bit & F_KIND) != 0 && (seen & F_KIND) != 0)) {
            throw new IllegalArgumentException("Duplicate or conflicting JSON-RPC field: " + field);
        }
        return seen | bit;
    }

    private static String requireString(JsonParser p, String field) {
        if (p.currentToken() != JsonToken.VALUE_STRING) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return p.getString();
    }

    private static JsonRpcMessage.Error parseError(JsonParser p) {
        if (p.currentToken() != JsonToken.START_OBJECT) {
            throw new IllegalArgumentException("error must be an object");
        }
        Integer code = null;
        String message = null;
        String dataJson = null;
        while (p.nextToken() != JsonToken.END_OBJECT) {
            String field = p.currentName();
            JsonToken token = p.nextToken();
            switch (field) {
                case CODE -> {
                    if (token != JsonToken.VALUE_NUMBER_INT || p.getNumberType() != JsonParser.NumberType.INT) {
                        throw new IllegalArgumentException("error.code must be an integer");
                    }
                    code = p.getIntValue();
                }
                case MESSAGE -> message = requireString(p, "error.message");
                case DATA -> dataJson = readRawJson(p);
                default -> p.skipChildren();
            }
        }
        if (code == null || message == null) {
            throw new IllegalArgumentException("error requires code and message");
        }
        return new JsonRpcMessage.Error(null, code, message, dataJson);
    }

    private static RequestId parseId(JsonParser p) {
        return switch (p.currentToken()) {
            case VALUE_NUMBER_INT -> RequestId.of(p.getLongValue());
            case VALUE_NUMBER_FLOAT -> RequestId.of(p.getDoubleValue());
            case VALUE_STRING -> RequestId.of(p.getString());
            default -> throw new IllegalArgumentException("Unexpected id token: " + p.currentToken());
        };
    }

    private static String readRawJson(JsonParser p) {
        var writer = new StringWriter(256);
        try (JsonGenerator gen = FACTORY.createGenerator(ObjectWriteContext.empty(), writer)) {
            gen.copyCurrentStructure(p);
        }
        return writer.toString();
    }

    /**
     * Deserializes a JSON string to a generic Java object (Map, List, String, Number, Boolean, or null).
     */
    public static @Nullable Object readValue(String json) {
        try (var p = FACTORY.createParser(ObjectReadContext.empty(), json)) {
            p.nextToken();
            return readGenericValue(p);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read JSON value", e);
        }
    }

    private static @Nullable Object readGenericValue(JsonParser p) throws IOException {
        return switch (p.currentToken()) {
            case START_OBJECT -> readObject(p);
            case START_ARRAY -> readArray(p);
            case VALUE_STRING -> p.getString();
            case VALUE_NUMBER_INT -> p.getLongValue();
            case VALUE_NUMBER_FLOAT -> p.getDoubleValue();
            case VALUE_TRUE -> Boolean.TRUE;
            case VALUE_FALSE -> Boolean.FALSE;
            case VALUE_NULL -> null;
            default -> throw new IOException("Unexpected token: " + p.currentToken());
        };
    }

    private static Map<String, Object> readObject(JsonParser p) throws IOException {
        Map<String, Object> map = new LinkedHashMap<>();
        while (p.nextToken() != JsonToken.END_OBJECT) {
            String key = p.currentName();
            p.nextToken();
            map.put(key, readGenericValue(p));
        }
        return map;
    }

    private static List<Object> readArray(JsonParser p) throws IOException {
        final List<Object> list = new ArrayList<>();
        while (p.nextToken() != JsonToken.END_ARRAY) {
            list.add(readGenericValue(p));
        }
        return list;
    }

    /**
     * Serializes JSON-RPC params to a JSON string: strings pass through as already-serialized JSON,
     * {@code null} becomes an empty object, everything else goes through {@link #writeValueAsString}.
     */
    public static String toJsonParams(@Nullable Object params) {
        return switch (params) {
            case null -> "{}";
            case String s -> s;
            default -> Objects.requireNonNull(writeValueAsString(params));
        };
    }

    /**
     * Serializes a Java object to a JSON string generically — maps, lists, scalars and JSON trees.
     * Protocol models carry no annotations here; they are serialized by the codecs of the protocol
     * version that built them, via {@code ProtocolResponseMapper.encode}.
     */
    public static @Nullable String writeValueAsString(@Nullable Object value) {
        return ValueSerializer.writeValueAsString(value);
    }

    /**
     * Serializes whatever {@code writer} emits into a JSON string, e.g. one protocol version's codec
     * writing a generated model.
     */
    public static String writeAsString(JsonWriter writer) {
        return new String(serialize(writer), StandardCharsets.UTF_8);
    }

    /** Writes a Java object as a JSON value via the given generator. */
    public static void writeJsonValue(JsonGenerator gen, @Nullable Object value) {
        ValueSerializer.writeJsonValue(gen, value);
    }

    private static byte[] serialize(JsonWriter writer) {
        // Plain byte[]: GC-managed, so a response dropped on the shutdown path is garbage, not a
        // pooled-buffer leak. The send side wraps it zero-copy via Unpooled.wrappedBuffer.
        try (var out = new ByteArrayOutputStream(256);
                JsonGenerator gen = FACTORY.createGenerator(ObjectWriteContext.empty(), out, JsonEncoding.UTF8)) {
            writer.write(gen);
            gen.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to serialize JSON", e);
        }
    }

    /**
     * Writes JSON into a generator; see {@link #writeAsString(JsonWriter)}.
     */
    @FunctionalInterface
    public interface JsonWriter {
        /**
         * Writes one JSON value.
         *
         * @param gen the generator to write to
         * @throws IOException on write failure
         */
        void write(JsonGenerator gen) throws IOException;
    }
}
