/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.json;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.api.json.spi.JsonSchemaFactory;
import java.util.Map;

/**
 * An immutable, encoded JSON Schema.
 *
 * <p>Both object and boolean schemas are supported. The server validates the schema against its
 * declared dialect, or JSON Schema 2020-12 when the dialect is absent.
 *
 * @author Konstantin Pavlov
 */
public interface JsonSchema extends JsonDocument {

    /**
     * Returns a schema that accepts any JSON object.
     *
     * @return a schema accepting any JSON object
     */
    static JsonSchema objectSchema() {
        return DefaultJsonSchema.OBJECT;
    }

    /**
     * Creates a schema from encoded JSON without parsing and verifying if the schema is correct.
     *
     * @param json the encoded JSON schema
     * @return the unchecked schema
     * @throws IllegalArgumentException when json is null or blank string
     */
    static JsonSchema unchecked(String json) {
        return new DefaultJsonSchema(JsonDocuments.requireContent(json));
    }

    /**
     * Same as {@link #unchecked(String)}
     *
     * @param json Json string
     * @throws IllegalArgumentException when json is null or blank string
     * @deprecated Use {@link #unchecked(String)}
     * @return the json schema
     */
    @Deprecated
    static JsonSchema of(String json) {
        return unchecked(json);
    }

    /**
     * Creates a schema from an already-parsed representation, via the {@link
     * JsonSchemaFactory} chain discovered through {@link java.util.ServiceLoader}: the
     * registered factory whose {@link JsonSchemaFactory#sourceType()} equals {@code type} wins.
     *
     * @param source the source representation, e.g. a Jackson {@code JsonNode}
     * @param type   the source representation's type
     * @return the parsed schema
     * @throws IllegalStateException if no {@code JsonSchemaFactory} is registered for {@code type}
     * @param <T> the value type
     */
    @ExperimentalApi
    static <T> JsonSchema from(T source, Class<T> type) {
        return JsonSchemas.from(source, type);
    }

    /**
     * Creates a schema from a {@link Map} representation of the schema, resolving it through the
     * {@link JsonSchemaFactory} registered for {@code Map} sources. Nested objects are themselves
     * {@code Map}s; pass a {@link java.util.LinkedHashMap} to keep key order stable in the encoded
     * schema.
     *
     * @param source the schema as a map, e.g. {@code {"type": "object", "properties": {...}}}
     * @return the resolved schema
     * @throws IllegalStateException if no {@code JsonSchemaFactory} is registered for {@code Map}
     *     sources
     */
    @ExperimentalApi(since = "1.0.0-beta.21")
    static JsonSchema from(Map<String, Object> source) {
        return JsonSchemas.from(source, Map.class);
    }

    /**
     * Resolves a schema for {@code type} through the {@link JsonSchemaFactory} resolution chain.
     * Registered factories are tried in ascending {@link JsonSchemaFactory#priority()} order and
     * the first non-empty result wins. The reference implementations ship with:
     *
     * <ul>
     *   <li>{@code KtSchemaResourceFactory} (tachyon-core) — loads a schema generated at build
     *       time, e.g. by the <a href="https://github.com/kpavlov/kt-schema">kt-schema</a>
     *       annotation processor, from {@code META-INF/kt-schema/schemas/<binary-class-name>.json}
     *       on {@code type}'s classloader.</li>
     *   <li>{@code KtSchemaReflectionFactory} (tachyon-kotlin-kt-schema) — generates the schema at
     *       runtime from {@code type}'s structure via reflection.</li>
     * </ul>
     *
     * <p>A build-time generated resource is therefore preferred automatically, and the runtime
     * reflection generator backstops types without one. The same chain also covers {@link
     * #from(Object, Class)}; both {@link #parse(String)} and this method resolve through it.
     *
     * @param type the type whose schema to resolve
     * @return the generated schema
     * @throws IllegalStateException if no registered {@link JsonSchemaFactory} produces a schema
     *     for {@code type}
     */
    @ExperimentalApi
    static JsonSchema generate(Class<?> type) {
        return JsonSchemas.generated(type);
    }

    /**
     * Use {@link #generate(Class)} instead.
     *
     * @param type the type whose schema to resolve
     * @return the generated schema
     */
    @ExperimentalApi
    @Deprecated
    static JsonSchema generated(Class<?> type) {
        return JsonSchema.generate(type);
    }

    /**
     * Resolves and validates a schema from encoded JSON, via the {@link JsonSchemaFactory} chain
     * discovered through {@link java.util.ServiceLoader}: the registered factory whose {@link
     * JsonSchemaFactory#sourceType()} is {@code String.class} validates {@code json} and produces
     * the schema. Delegates to {@link #from(Object, Class)} ({@code from(json, String.class)}).
     *
     * <p>This API is {@link ExperimentalApi} and may change in a future release.
     *
     * @param json the encoded JSON schema
     * @return the parsed schema
     * @throws IllegalArgumentException when {@code json} is not valid JSON
     * @throws IllegalStateException    if no {@code JsonSchemaFactory<String>} is registered for
     *                                  {@code String} sources
     */
    @ExperimentalApi
    static JsonSchema parse(String json) {
        return JsonSchema.from(json, String.class);
    }
}
