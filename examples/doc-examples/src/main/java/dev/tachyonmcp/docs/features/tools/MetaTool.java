package dev.tachyonmcp.docs.features.tools;

import dev.tachyonmcp.api.annotations.McpTool;
import dev.tachyonmcp.api.server.features.tools.ToolResult;

final class MetaTool {

    @McpTool
    public ToolResult finish() {
        // snips-start: tools_meta
        return ToolResult.text("done").withMeta("taskId", "t-123");
        // snips-end: tools_meta
    }
}
