package dev.tachyonmcp.docs.running.configuration;

import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.features.tools.AbstractToolHandler;
import dev.tachyonmcp.api.server.features.tools.ToolDescriptor;
import dev.tachyonmcp.api.server.features.tools.ToolRequest;
import dev.tachyonmcp.api.server.features.tools.ToolResult;

import static dev.tachyonmcp.docs.running.configuration.SlowWork.doSlowStep;
import static dev.tachyonmcp.docs.running.configuration.SlowWork.total;

// snips-start: config_slow_tool
class SlowTool extends AbstractToolHandler {
    SlowTool() {
        super(ToolDescriptor.builder().name("slow-task").description("Long task, kept alive").build());
    }

    @Override
    public ToolResult handle(InteractionContext ctx, ToolRequest request) throws Exception {
        var token = request.progressToken();          // client _meta.progressToken; null if absent
        for (int i = 0; i < total; i++) {
            // First call upgrades POST → SSE and arms the heartbeat.
            if (token != null) {
                ctx.notifications().progress(token, i, total, "step " + i);
            } else {
                ctx.notifications().comment("step " + i);   // token-free keep-alive
            }
            doSlowStep(i);
        }
        return ToolResult.text("done");
    }
}
// snips-end: config_slow_tool
