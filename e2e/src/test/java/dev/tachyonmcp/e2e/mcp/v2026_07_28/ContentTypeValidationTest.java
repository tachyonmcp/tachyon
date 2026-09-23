/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2026_07_28;

import dev.tachyonmcp.e2e.mcp.AbstractContentTypeValidationTest;
import dev.tachyonmcp.testkit.Mcp20260728Client;
import dev.tachyonmcp.testkit.McpTestClients;
import java.util.Map;

class ContentTypeValidationTest extends AbstractContentTypeValidationTest<Mcp20260728Client> {

    @Override
    protected Mcp20260728Client createTestClient() {
        return createTestClient(port);
    }

    @Override
    protected Mcp20260728Client createTestClient(int port) {
        return McpTestClients.latest(port);
    }

    @Override
    protected String toolCallBody(String tag) {
        // language=JSON
        return """
                {
                  "jsonrpc":"2.0",
                  "id":1,
                  "method":"tools/call",
                  "params":{
                    "name":"record",
                    "arguments":{"tag":"%s"},
                    "_meta":{
                      "io.modelcontextprotocol/protocolVersion":"2026-07-28",
                      "io.modelcontextprotocol/clientInfo":{"name":"test","version":"1.0"},
                      "io.modelcontextprotocol/clientCapabilities":{}
                    }
                  }
                }
                """.formatted(tag);
    }

    @Override
    protected Map<String, String> requestHeaders() {
        return Map.of("MCP-Protocol-Version", "2026-07-28", "Mcp-Method", "tools/call", "Mcp-Name", "record");
    }
}
