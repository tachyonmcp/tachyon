/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2025_11_25;

import dev.tachyonmcp.e2e.mcp.AbstractCorsAllowedOriginsTest;
import dev.tachyonmcp.testkit.Mcp20251125Client;

class CorsAllowedOriginsTest extends AbstractCorsAllowedOriginsTest<Mcp20251125Client> {

    @Override
    protected Mcp20251125Client createTestClient() {
        return createTestClient(port);
    }

    @Override
    protected Mcp20251125Client createTestClient(int port) {
        return new Mcp20251125Client(port);
    }

    @Override
    protected String requestBody() {
        // language=JSON
        return """
                {
                  "jsonrpc":"2.0",
                  "id":1,
                  "method":"initialize",
                  "params":{
                    "protocolVersion":"2025-11-25",
                    "capabilities":{},
                    "clientInfo":{"name":"test","version":"1.0"}
                  }
                }
                """;
    }
}
