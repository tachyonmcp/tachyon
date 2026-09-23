/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2025_11_25;

import dev.tachyonmcp.e2e.mcp.AbstractExtensionsTest;
import dev.tachyonmcp.testkit.Mcp20251125Client;
import java.net.http.HttpResponse;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Stateful: 2025-11-25 declares extensions once in {@code initialize} and only a session carries that
 * declaration to later requests. The stateless variant is {@link StatelessExtensionNegotiationTest}.
 */
class ExtensionsTest extends AbstractExtensionsTest<Mcp20251125Client> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
    protected HttpResponse<String> advertise(Map<String, JsonNode> extensions) throws Exception {
        try (var client = createTestClient()) {
            return client.post(null, initializeRequest(extensions));
        }
    }

    @Override
    protected Mcp20251125Client negotiatedClient(Map<String, JsonNode> extensions) throws Exception {
        var client = createTestClient();
        var response = client.post(null, initializeRequest(extensions));
        client.sendInitialized(response.headers().firstValue("MCP-Session-Id").orElseThrow());
        return client;
    }

    @Override
    protected HttpResponse<String> send(Mcp20251125Client client, String body) throws Exception {
        return client.post(client.sessionId(), body);
    }

    @Override
    protected int invalidParamsHttpStatus() {
        return 200;
    }

    private static String initializeRequest(Map<String, JsonNode> extensions) {
        // language=JSON
        return """
                {
                  "jsonrpc":"2.0",
                  "id":1,
                  "method":"initialize",
                  "params":{
                    "protocolVersion":"2025-11-25",
                    "capabilities":{"extensions":%s},
                    "clientInfo":{"name":"test-client","version":"1.0"}
                  }
                }
                """.formatted(MAPPER.writeValueAsString(extensions));
    }
}
