package dev.tachyonmcp.docs.json;

import dev.tachyonmcp.api.json.JsonSchemaValidator;
import dev.tachyonmcp.api.json.SchemaValidationError;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.TachyonServer;
import java.util.List;
import java.util.Map;

public final class CustomValidatorsServer {

    private CustomValidatorsServer() {}

    public static void main(String[] args) {
        JsonSchemaValidator myInputValidator = (schema, document) -> document.json().contains("forbidden")
                ? List.of(new SchemaValidationError("$", "custom", "forbidden word"))
                : List.of();
        // snips-start: json_validators
        var server = TachyonServer.builder()
            .json(json -> json
                .inputSchemaValidator(myInputValidator)
                .outputSchemaValidator(JsonSchemaValidator.noop()))
            .port(8080)
            .build();
        // snips-end: json_validators
        server.tools().register(
                tool -> tool.name("weather")
                        .inputSchema(JsonSchemasHolder.WEATHER_INPUT)
                        .outputSchema(JsonSchemasHolder.WEATHER_OUTPUT),
                (ctx, request) -> ToolResult.structured(Map.of("temp", "hot")));
        server.start();
    }
}
