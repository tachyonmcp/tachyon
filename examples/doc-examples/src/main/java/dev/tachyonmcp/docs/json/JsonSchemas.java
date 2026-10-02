package dev.tachyonmcp.docs.json;

import dev.tachyonmcp.api.json.JsonDocument;
import dev.tachyonmcp.api.json.JsonSchema;

final class JsonSchemas {

    private JsonSchemas() {}

    static JsonSchema literal() {
        // snips-start: json_schema_unchecked
        var inputSchema = JsonSchema.unchecked("""
            {
              "type": "object",
              "properties": {
                "city": { "type": "string" },
                "days": { "type": "integer", "minimum": 1 }
              },
              "required": ["city"]
            }
            """);
        // snips-end: json_schema_unchecked
        return inputSchema;
    }

    static JsonSchema external(String userSuppliedSchema) {
        // snips-start: json_schema_parse
        JsonSchema inputSchema = JsonSchema.parse(userSuppliedSchema);
        // snips-end: json_schema_parse
        return inputSchema;
    }

    record Documents(JsonDocument trusted, JsonDocument checked) {}

    static Documents documents(String jsonLiteral, String externalJson) {
        // snips-start: json_document_factories
        JsonDocument trusted = JsonDocument.of(jsonLiteral);
        JsonDocument checked = JsonDocument.parse(externalJson);
        // snips-end: json_document_factories
        return new Documents(trusted, checked);
    }
}
