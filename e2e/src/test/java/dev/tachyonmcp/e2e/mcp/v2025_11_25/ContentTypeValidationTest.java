/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2025_11_25;

import dev.tachyonmcp.e2e.mcp.AbstractContentTypeValidationTest;
import dev.tachyonmcp.testkit.Mcp20251125Client;
import java.util.Map;

class ContentTypeValidationTest extends AbstractContentTypeValidationTest<Mcp20251125Client> {

    @Override
    protected Mcp20251125Client createTestClient() {
        return createTestClient(port);
    }

    @Override
    protected Mcp20251125Client createTestClient(int port) {
        return new Mcp20251125Client(port);
    }

    @Override
    protected String toolCallBody(String tag) {
        // language=JSON
        return """
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"record","arguments":{"tag":"%s"}}}
                """.formatted(tag);
    }

    @Override
    protected Map<String, String> requestHeaders() {
        return Map.of("MCP-Protocol-Version", "2025-11-25");
    }
}
