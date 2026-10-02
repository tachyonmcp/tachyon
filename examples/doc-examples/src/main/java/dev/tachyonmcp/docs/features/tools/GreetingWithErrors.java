package dev.tachyonmcp.docs.features.tools;

import dev.tachyonmcp.api.annotations.McpTool;
import dev.tachyonmcp.api.server.features.tools.ToolResult;

final class GreetingWithErrors {

    // snips-start: tools_greet_errors
    @McpTool(description = "Say hello to someone")
    public ToolResult greet(String name) {
        if (name.isBlank()) {
            return ToolResult.error("Provide a non-blank name.");
        }
        return ToolResult.text("Hello, " + name + "!");
    }
    // snips-end: tools_greet_errors
}
