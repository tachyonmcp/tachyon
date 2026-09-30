/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2026_07_28;

import dev.tachyonmcp.e2e.mcp.AbstractToolErrorContractTest;
import dev.tachyonmcp.testkit.Mcp20260728Client;
import dev.tachyonmcp.testkit.McpTestClients;

class ToolErrorContractTest extends AbstractToolErrorContractTest<Mcp20260728Client> {

    @Override
    protected Mcp20260728Client createTestClient() {
        return createTestClient(port);
    }

    @Override
    protected Mcp20260728Client createTestClient(int port) {
        return McpTestClients.latest(port);
    }

    @Override
    protected Mcp20260728Client readyClient() {
        return createModernTestClient();
    }

    @Override
    protected int invalidParamsHttpStatus() {
        return 400;
    }
}
