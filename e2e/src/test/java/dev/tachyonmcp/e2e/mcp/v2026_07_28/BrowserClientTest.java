/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2026_07_28;

import dev.tachyonmcp.e2e.mcp.AbstractBrowserClientTest;
import dev.tachyonmcp.testkit.Mcp20260728Client;
import dev.tachyonmcp.testkit.McpTestClients;
import java.util.List;

/** Stateless: every POST carries the SEP-2243 mirrors, including a tool's {@code Mcp-Param-*}. */
class BrowserClientTest extends AbstractBrowserClientTest<Mcp20260728Client> {

    @Override
    protected SessionMode sessionMode() {
        return SessionMode.STATELESS;
    }

    @Override
    protected Mcp20260728Client createTestClient() {
        return createTestClient(port);
    }

    @Override
    protected Mcp20260728Client createTestClient(int port) {
        return McpTestClients.latest(port);
    }

    @Override
    protected List<String> clientHeaders() {
        return List.of(
                "authorization", "content-type", "mcp-method", "mcp-name", "mcp-param-region", "mcp-protocol-version");
    }

    @Override
    protected List<String> clientMethods() {
        return List.of("POST");
    }

    @Override
    protected String firstRequestBody() {
        // language=JSON
        return """
                {"jsonrpc":"2.0","id":1,"method":"tools/list"}
                """;
    }

    @Override
    protected List<String> scriptReadableHeaders() {
        return List.of();
    }
}
