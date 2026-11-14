/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.json;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialect;
import com.networknt.schema.dialect.Dialects;
import com.networknt.schema.keyword.NonValidationKeyword;
import dev.tachyonmcp.api.json.JsonDocument;
import dev.tachyonmcp.api.json.JsonSchema;
import dev.tachyonmcp.api.json.JsonSchemaValidator;
import dev.tachyonmcp.api.json.SchemaValidationError;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** JSON Schema validator backed by {@code networknt/json-schema-validator}. */
public class NetworkntJsonSchemaValidator implements JsonSchemaValidator {

    private static final Dialect MCP_DIALECT = Dialect.builder(Dialects.getDraft202012())
            .keyword(new NonValidationKeyword("x-mcp-header"))
            .build();

    private final SchemaRegistry registry;

    private final ConcurrentMap<String, Schema> compiledSchemas = new ConcurrentHashMap<>();

    public static final NetworkntJsonSchemaValidator INSTANCE = new NetworkntJsonSchemaValidator();

    public NetworkntJsonSchemaValidator() {
        this.registry = SchemaRegistry.withDialect(MCP_DIALECT);
    }

    @Override
    public List<SchemaValidationError> validate(JsonSchema schema, JsonDocument document) {
        var compiledSchema = compiledSchemas.computeIfAbsent(schema.json(), ignored -> compile(schema));
        var errors = compiledSchema.validate(JsonUtils.parse(document));
        if (errors.isEmpty()) {
            return List.of();
        }
        return errors.stream()
                .map(error -> new SchemaValidationError(
                        error.getInstanceLocation().toString(), error.getKeyword(), error.getMessage()))
                .toList();
    }

    private Schema compile(JsonSchema schema) {
        var location = SchemaLocation.of(
                "urn:tachyon:schema:" + Integer.toHexString(schema.json().hashCode()));
        return registry.getSchema(location, JsonUtils.parse(schema));
    }
}
