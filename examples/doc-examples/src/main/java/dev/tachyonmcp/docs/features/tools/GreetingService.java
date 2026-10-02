package dev.tachyonmcp.docs.features.tools;

// snips-start: tools_greeting_service
import dev.tachyonmcp.api.annotations.McpTool;

class GreetingService {
    @McpTool(description = "Say hello to someone")
    public String greet(String name) {
        return "Hello, " + name + "!";
    }
}
// snips-end: tools_greeting_service
