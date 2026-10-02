package dev.tachyonmcp.docs.features.tools;

import dev.tachyonmcp.api.annotations.McpTool;

final class WelcomeService {

    // snips-start: tools_welcome
    @McpTool
    public String welcome(String name, @org.jspecify.annotations.Nullable String title) {
        return "Hello, " + (title == null ? "" : title + " ") + name + "!";
    }
    // snips-end: tools_welcome
}
