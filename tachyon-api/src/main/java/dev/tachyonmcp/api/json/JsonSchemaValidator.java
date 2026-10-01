/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.json;

import java.util.List;

/**
 * Validates JSON data against a JSON Schema.
 *
 * @author Konstantin Pavlov
 */
@FunctionalInterface
public interface JsonSchemaValidator {

    /**
     * Validates the given JSON document against the schema and returns any errors.
     *
     * @param schema the JSON schema
     * @param document the JSON document
     * @return the validation errors
     */
    List<SchemaValidationError> validate(JsonSchema schema, JsonDocument document);

    /**
     * Returns the no-op validator singleton that accepts all input. Passing this instance disables
     * validation entirely, including the parsing work needed to prepare data for validation.
     *
     * @return the no-op validator
     */
    static JsonSchemaValidator noop() {
        return NoopJsonSchemaValidator.INSTANCE;
    }
}

final class NoopJsonSchemaValidator implements JsonSchemaValidator {

    static final JsonSchemaValidator INSTANCE = new NoopJsonSchemaValidator();

    private NoopJsonSchemaValidator() {}

    @Override
    public List<SchemaValidationError> validate(JsonSchema schema, JsonDocument document) {
        return List.of();
    }
}
