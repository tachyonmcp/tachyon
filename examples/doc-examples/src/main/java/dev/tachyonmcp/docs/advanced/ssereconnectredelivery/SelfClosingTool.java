package dev.tachyonmcp.docs.advanced.ssereconnectredelivery;

import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.features.tools.ToolRequest;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.OutboundSseStreamMessageRouter;

public final class SelfClosingTool {

    private SelfClosingTool() {}

    public static ToolResult handle(InteractionContext ctx, ToolRequest request) throws InterruptedException {
        // snips-start: sse_self_closing_tool
        var stream = OutboundSseStreamMessageRouter.currentOutboundSseStream();
        stream.start(); // upgrades POST → SSE, sends the priming event  (id 4#3)
        stream.close(); // closes the channel; the client observes a disconnect
        // ...tool keeps working, then returns its result
        // snips-end: sse_self_closing_tool
        Thread.sleep(300);
        return ToolResult.text("resumed-payload");
    }
}
