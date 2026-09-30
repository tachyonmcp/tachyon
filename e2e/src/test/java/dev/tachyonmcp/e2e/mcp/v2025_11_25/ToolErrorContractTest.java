/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2025_11_25;

import dev.tachyonmcp.e2e.mcp.AbstractToolErrorContractTest;
import dev.tachyonmcp.testkit.Mcp20251125Client;

class ToolErrorContractTest extends AbstractToolErrorContractTest<Mcp20251125Client> {

    @Override
    protected Mcp20251125Client createTestClient() {
        return createTestClient(port);
    }

    @Override
    protected Mcp20251125Client createTestClient(int port) {
        return new Mcp20251125Client(port);
    }

    @Override
    protected Mcp20251125Client readyClient() throws Exception {
        var client = createTestClient();
        client.initialize();
        return client;
    }

    @Override
    protected int invalidParamsHttpStatus() {
        return 200;
    }
}
