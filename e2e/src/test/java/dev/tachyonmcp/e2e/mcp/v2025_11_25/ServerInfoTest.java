/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2025_11_25;

import static dev.tachyonmcp.testkit.JsonRpcResponseAssert.assertThat;

import dev.tachyonmcp.api.server.domain.Icon;
import dev.tachyonmcp.e2e.mcp.AbstractStatelessMcpE2eTest;
import dev.tachyonmcp.extensions.tasks.TasksExtension;
import dev.tachyonmcp.testkit.Mcp20251125Client;
import dev.tachyonmcp.testkit.McpClient;
import dev.tachyonmcp.testkit.TestTaskConnector;
import java.util.List;
import org.junit.jupiter.api.Test;

class ServerInfoTest extends AbstractStatelessMcpE2eTest<McpClient> {

    @Override
    protected Mcp20251125Client createTestClient() {
        return createTestClient(port);
    }

    @Override
    protected Mcp20251125Client createTestClient(int port) {
        return new Mcp20251125Client(port);
    }

    @Test
    void allCapabilitiesEnabled() throws Exception {
        var taskEngine = new TestTaskConnector();
        startServer(it -> it.info(b -> b.name("test-server")
                        .version("2.0")
                        .description("Test server")
                        .title("Test Server")
                        .websiteUrl("https://example.com/mcp")
                        .instructions("Test instructions")
                        .icons(Icon.of("https://example.com/icon.png", "image/png", List.of("32x32"), "light")))
                .capabilities(c -> c.completions()
                        .logging()
                        .prompts(true)
                        .tools(true)
                        .resources(true, true)
                        .prompts(true))
                .withExtension(TasksExtension.class, t -> t.connector(taskEngine.connector())));

        try (var client = createTestClient()) {
            // language=json
            final var response = client.sendRpc("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}
                """);

            // language=json
            assertThat(response).isSuccess().hasId(1).hasResult("""
                {
                  "protocolVersion": "2025-11-25",
                  "serverInfo": {
                    "name": "test-server",
                    "version": "2.0",
                    "description": "Test server",
                    "title": "Test Server",
                    "websiteUrl": "https://example.com/mcp",
                    "icons": [{
                      "src": "https://example.com/icon.png",
                      "mimeType": "image/png",
                      "sizes": ["32x32"],
                      "theme": "light"
                    }]
                  },
                  "instructions": "Test instructions",
                  "capabilities": {
                    "logging": {},
                    "completions": {},
                    "tools": {
                      "listChanged": true
                    },
                    "resources": {
                      "subscribe": true,
                      "listChanged": true
                    },
                    "prompts": {
                      "listChanged": true
                    },
                    "tasks": {
                      "list": {},
                      "cancel": {},
                      "requests": {
                        "tools": {
                          "call": {}
                        }
                      }
                    },
                    "extensions": {
                      "io.modelcontextprotocol/tasks": {}
                    }
                  }
                }
                """);
        }
    }

    @Test
    void minimalisticServer() throws Exception {
        startServer(it -> it.capabilities(c -> c.noTools().noResources().noPrompts()));

        try (var client = createTestClient()) {
            // language=json
            final var response = client.sendRpc(null, """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}
                """);

            // language=json
            assertThat(response).isSuccess().hasId(1).hasResult("""
                {
                  "protocolVersion":"2025-11-25",
                  "serverInfo":{"version":"0.1","name":"tachyon-mcp"},
                  "capabilities": {}
                }
                """);
        }
    }
}
