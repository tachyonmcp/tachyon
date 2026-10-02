package dev.tachyonmcp.docs.features;

import dev.tachyonmcp.api.annotations.McpTool;

public final class GreetingService {

    @McpTool(description = "Say hello to someone")
    public String greet(String name) {
        return "Hello, " + name + "!";
    }
}
