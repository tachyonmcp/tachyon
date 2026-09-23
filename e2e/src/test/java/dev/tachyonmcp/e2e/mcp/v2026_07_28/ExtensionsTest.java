/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2026_07_28;

import dev.tachyonmcp.e2e.mcp.AbstractExtensionsTest;
import dev.tachyonmcp.testkit.Mcp20260728Client;
import dev.tachyonmcp.testkit.McpTestClients;
import java.net.http.HttpResponse;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** Stateless: 2026-07-28 declares extensions per request and advertises them via {@code server/discover}. */
class ExtensionsTest extends AbstractExtensionsTest<Mcp20260728Client> {

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
    protected HttpResponse<String> advertise(Map<String, JsonNode> extensions) throws Exception {
        try (var client = negotiatedClient(extensions)) {
            return client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"server/discover"}
                    """);
        }
    }

    @Override
    protected Mcp20260728Client negotiatedClient(Map<String, JsonNode> extensions) {
        return createTestClient().withExtensions(extensions);
    }

    @Override
    protected HttpResponse<String> send(Mcp20260728Client client, String body) throws Exception {
        return client.post(body);
    }

    @Override
    protected int invalidParamsHttpStatus() {
        return 400;
    }
}
