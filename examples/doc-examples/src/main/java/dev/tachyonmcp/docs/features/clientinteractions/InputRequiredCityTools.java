package dev.tachyonmcp.docs.features.clientinteractions;

import dev.tachyonmcp.api.annotations.McpTool;
import dev.tachyonmcp.api.json.JsonSchema;
import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.domain.FormInputRequest;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import java.util.Map;

final class InputRequiredCityTools {
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
        // snips-start: ci_input_required
        return ToolResult.inputRequired(
                Map.of("city", FormInputRequest.of("Choose a forecast city", CITY_SCHEMA)),
                "forecast-draft-42");
        // snips-end: ci_input_required
    }
}
