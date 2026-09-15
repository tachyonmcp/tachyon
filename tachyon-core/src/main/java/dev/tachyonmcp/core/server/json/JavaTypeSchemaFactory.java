/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.json;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.api.json.JsonSchema;
import dev.tachyonmcp.api.json.spi.JsonSchemaFactory;
import java.util.Optional;

/**
 * Last-resort {@link JsonSchemaFactory} that generates object schemas for plain Java records,
 * POJOs, and maps by reflection (see {@link JavaTypeSchemas}), so {@link JsonSchema#generate(Class)}
 * works on a Java-only classpath. Runs after build-time codegen resources and kt-schema reflection;
 * declines non-object types so the chain still fails for them.
 *
 * @author Konstantin Pavlov
 */
@ExperimentalApi
public final class JavaTypeSchemaFactory implements JsonSchemaFactory<Class<?>> {

    /**
     * Creates the factory. Public no-arg constructor required for {@link java.util.ServiceLoader}
     * discovery via {@code META-INF/services}.
     */
    public JavaTypeSchemaFactory() {}

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Class<Class<?>> sourceType() {
        return (Class) Class.class;
    }

    @Override
    public int priority() {
        return 1000;
    }

    @Override
    public Optional<JsonSchema> toJsonSchema(Class<?> type) {
        if (!JavaTypeSchemas.isObjectType(type)) {
            return Optional.empty();
        }
        return Optional.of(JsonSchema.from(JavaTypeSchemas.schemaFor(type)));
    }
}
