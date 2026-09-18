/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.conformance;

import dev.tachyonmcp.api.server.domain.LoggingLevel;
import dev.tachyonmcp.api.server.domain.PromptMessage;
import dev.tachyonmcp.api.server.features.prompts.PromptDescriptor;
import dev.tachyonmcp.api.server.features.prompts.PromptResult;
import dev.tachyonmcp.api.server.features.tools.ToolDescriptor;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.server.config.SessionConfig;
import dev.tachyonmcp.core.server.domain.MissingRequiredClientCapabilityException;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import java.util.List;
import java.util.Map;

class EdgeConformanceServer extends AbstractConformanceServer {

    @Override
    protected ServerEngine createServer(boolean isStateful) {
        final var builder = TachyonServer.builder();
        if (isStateful) {
            builder.session(SessionConfig.Builder::enabled);
        }
        return (ServerEngine) builder.withTools(tools -> tools.register(
                                ToolDescriptor.builder()
                                        .name("test_missing_capability")
                                        .description("SEP-2575 requires an explicitly declared capability")
                                        .inputSchema(INPUT_SCHEMA_NO_ARGS)
                                        .build(),
                                (ctx, request) -> {
                                    var meta = request.meta();
                                    var capabilities = meta != null
                                            ? meta.get("io.modelcontextprotocol/clientCapabilities")
                                            : null;
                                    var hasSampling = field(capabilities, "sampling") != null;
                                    if (!hasSampling) {
                                        throw new MissingRequiredClientCapabilityException(
                                                "Requires the 'sampling' capability", Map.of("sampling", Map.of()));
                                    }
                                    return ToolResult.text("sampling capability present");
                                })
                        .register(
                                ToolDescriptor.builder()
                                        .name("test_logging_tool")
                                        .description(
                                                "SEP-2575 emits a log message; must be suppressed without _meta.../logLevel")
                                        .inputSchema(INPUT_SCHEMA_NO_ARGS)
                                        .build(),
                                (ctx, request) -> {
                                    ctx.notifications().log(LoggingLevel.INFO, "test", "diagnostic log message");
                                    return ToolResult.text("logged");
                                })
                        .register(
                                ToolDescriptor.builder()
                                        .name("test_streaming_elicitation")
                                        .description(
                                                "SEP-2575 response stream carries only notifications, never independent requests")
                                        .inputSchema(INPUT_SCHEMA_NO_ARGS)
                                        .build(),
                                (ctx, request) -> {
                                    ctx.notifications().progress(request.progressToken(), 1, 1, "working");
                                    return ToolResult.text("streamed");
                                })
                        .register(
                                ToolDescriptor.builder()
                                        .name("test_custom_header")
                                        .description("SEP-2243 x-mcp-header/Mcp-Param-* custom header validation")
                                        .inputSchema(
                                                // language=json
                                                """
                            {
                              "type": "object",
                              "properties": {
                                "region": {"type": "string", "x-mcp-header": "Region"},
                                "query": {"type": "string"}
                              },
                              "required": ["region", "query"]
                            }
                            """)
                                        .build(),
                                (ctx, request) -> {
                                    var args = request.arguments();
                                    var region = args.stringOpt("region");
                                    var query = args.stringOpt("query");
                                    return ToolResult.text(
                                            "region=" + region.orElse("") + " query=" + query.orElse(""));
                                }))
                .build();
    }

    /**
     * SEP-2322 InputRequiredResult tools, draft-only (protocol version 2026-07-28): the
     * {@code input-required-result-*} conformance scenarios don't apply to the stable suite.
     */
    @Override
    protected void registerVersionSpecificTools(ServerEngine server) {
        registerInputRequiredTools(server);
    }

    @Override
    protected void registerVersionSpecificPrompts(ServerEngine server) {
        server.prompts()
                .register(
                        PromptDescriptor.of(
                                "test_input_required_result_prompt", "Prompt requiring elicitation input (SEP-2322)"),
                        (ctx, request) -> {
                            var inputResponses = request.inputResponses();
                            if (inputResponses != null && inputResponses.containsKey("user_context")) {
                                return PromptResult.messages(List.of(PromptMessage.user("Context received")));
                            }
                            return PromptResult.inputRequired(
                                    Map.of(
                                            "user_context",
                                            buildFormElicitation(
                                                    "What context should the prompt use?", "context", "string")),
                                    null);
                        });
    }
}
