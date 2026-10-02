package dev.tachyonmcp.docs.features.clientinteractions;

// snips-start: ci_city_tools
import dev.tachyonmcp.api.annotations.McpTool;
import dev.tachyonmcp.api.json.JsonSchema;
import dev.tachyonmcp.api.runtime.ElicitationRequest;
import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.features.tools.ToolResult;

class CityTools {
    private static final JsonSchema CITY_SCHEMA = JsonSchema.unchecked("""
            {
              "type": "object",
              "properties": {
                "city": { "type": "string" }
              },
              "required": ["city"]
            }
            """);

    @McpTool(name = "choose-city", description = "Ask the user to choose a forecast city")
    public ToolResult chooseCity(InteractionContext context) {
        var result = context.client().elicitation().create(
                ElicitationRequest.builder()
                        .message("Choose a forecast city")
                        .requestedSchema(CITY_SCHEMA)
                        .build()).join();

        return switch (result.action()) {
            case ACCEPT -> ToolResult.text("Selected " + result.content().stringValue("city"));
            case DECLINE -> ToolResult.error("City selection declined");
            case CANCEL -> ToolResult.error("City selection cancelled");
        };
    }
}
// snips-end: ci_city_tools
