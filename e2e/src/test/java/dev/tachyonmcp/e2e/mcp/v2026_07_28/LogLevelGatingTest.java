/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2026_07_28;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.server.domain.LoggingLevel;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.config.CapabilitiesConfig;
import dev.tachyonmcp.e2e.mcp.AbstractStatelessMcpE2eTest;
import dev.tachyonmcp.testkit.Mcp20260728Client;
import dev.tachyonmcp.testkit.McpClient;
import dev.tachyonmcp.testkit.McpTestClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MCP 2026-07-28 (SEP-2575): logging is per-request, not session-scoped. A server MUST NOT send
 * {@code notifications/message} for a request that did not set {@code _meta.../logLevel} — there
 * is no {@code logging/setLevel} RPC and no session to carry a standing threshold. When the
 * request does set a level, only messages at or above it may be sent.
 */
class LogLevelGatingTest extends AbstractStatelessMcpE2eTest<McpClient> {

    @Override
    protected Mcp20260728Client createTestClient() {
        return createTestClient(port);
    }

    @Override
    protected Mcp20260728Client createTestClient(int port) {
        return McpTestClients.latest(port);
    }

    // language=JSON
    private static final String NO_ARGS_SCHEMA = "{\"type\": \"object\", \"properties\": {}}";

    @BeforeEach
    void registerFixtures() {
        startServer(
                b -> b.capabilities(CapabilitiesConfig.Builder::logging),
                s -> s.tools()
                        .register(
                                tool -> tool.name("test_logging_tool")
                                        .description("Emits an INFO log message then completes")
                                        .inputSchema(NO_ARGS_SCHEMA),
                                (ctx, request) -> {
                                    ctx.notifications().log(LoggingLevel.INFO, "test", "hello");
                                    return ToolResult.text("done");
                                }));
    }

    @Test
    @DisplayName("[SEP-2575] no log message for a request that set no logLevel")
    void suppressesLogWithoutRequestLogLevel() throws Exception {
        try (var client = createModernTestClient()) {
            var response = client.sendStreamingRequest(null, """
                    {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": "test_logging_tool", "arguments": {}}}
                    """);
            var lines = response.body().toList();

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(lines).noneMatch(line -> line.contains("notifications/message"));
            assertThat(lines).anyMatch(line -> line.contains("\"result\""));
        }
    }

    @Test
    @DisplayName("[SEP-2575][SEP-2577] deprecated logging still delivered at or above the request logLevel")
    void emitsLogWhenRequestLogLevelPermits() throws Exception {
        try (var client = createModernTestClient()) {
            var response = client.sendStreamingRequest(null, """
                    {"jsonrpc": "2.0", "id": 2, "method": "tools/call",
                     "params": {"name": "test_logging_tool", "arguments": {},
                                "_meta": {"io.modelcontextprotocol/logLevel": "info"}}}
                    """);
            var lines = response.body().toList();

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(lines)
                    .anyMatch(line -> line.contains("notifications/message") && line.contains("\"level\":\"info\""));
        }
    }
}
