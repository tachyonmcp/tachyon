/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2025_11_25;

import dev.tachyonmcp.e2e.mcp.AbstractBrowserClientTest;
import dev.tachyonmcp.testkit.Mcp20251125Client;
import java.util.List;

/** Stateful: the browser must read {@code MCP-Session-Id}, resume with GET and close with DELETE. */
class BrowserClientTest extends AbstractBrowserClientTest<Mcp20251125Client> {

    @Override
    protected SessionMode sessionMode() {
        return SessionMode.STATEFUL;
    }

    @Override
    protected Mcp20251125Client createTestClient() {
        return createTestClient(port);
    }

    @Override
    protected Mcp20251125Client createTestClient(int port) {
        return new Mcp20251125Client(port);
    }

    @Override
    protected List<String> clientHeaders() {
        return List.of("authorization", "content-type", "last-event-id", "mcp-protocol-version", "mcp-session-id");
    }

    @Override
    protected List<String> clientMethods() {
        return List.of("POST", "GET", "DELETE");
    }

    @Override
    protected String firstRequestBody() {
        // language=JSON
        return """
                {
                  "jsonrpc":"2.0",
                  "id":1,
                  "method":"initialize",
                  "params":{
                    "protocolVersion":"2025-11-25",
                    "capabilities":{},
                    "clientInfo":{"name":"browser","version":"1.0"}
                  }
                }
                """;
    }

    @Override
    protected List<String> scriptReadableHeaders() {
        return List.of("mcp-session-id");
    }
}
