/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.json;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.api.json.JsonSchema;
import dev.tachyonmcp.api.json.spi.JsonSchemaFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * {@link JsonSchemaFactory} that loads a schema generated at build time by the
 * <a href="https://github.com/kpavlov/kt-schema">kt-schema</a> annotation processor from
 * {@code META-INF/kt-schema/schemas/<binary-class-name>.json} on the target class's classloader.
 *
 * <p>Exposes {@link Class} as its {@link #sourceType()}: each call passes the target {@link Class}
 * as the source and loads that type's codegen resource, returning {@link Optional#empty()} when
 * none exists so the resolution chain continues to a lower-priority factory (e.g. the runtime
 * reflection generator). Ships in {@code tachyon-core} so Java-only servers resolve codegen schemas
 * without adding a Kotlin integration artifact.
 *
 * @author Konstantin Pavlov
 */
@ExperimentalApi
public final class KtSchemaResourceFactory implements JsonSchemaFactory<Class<?>> {

    /**
     * Creates the factory. Public no-arg constructor required for {@link
     * java.util.ServiceLoader} discovery via {@code META-INF/services}.
     */
    public KtSchemaResourceFactory() {}

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Class<Class<?>> sourceType() {
        return (Class) Class.class;
    }

    @Override
    public Optional<JsonSchema> toJsonSchema(Class<?> type) {
        var path = "META-INF/kt-schema/schemas/%s.json".formatted(type.getName().replace('.', '/'));
        var loader = type.getClassLoader() != null ? type.getClassLoader() : ClassLoader.getSystemClassLoader();
        try (var in = loader.getResourceAsStream(path)) {
            if (in == null) {
                return Optional.empty();
            }
            return Optional.of(JsonSchema.unchecked(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read schema resource: " + path, e);
        }
    }
}
