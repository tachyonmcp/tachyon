/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2025_11_25;

import static dev.tachyonmcp.testkit.JsonRpcResponseAssert.assertThat;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.core.runtime.Session;
import dev.tachyonmcp.core.runtime.SessionState;
import dev.tachyonmcp.e2e.mcp.AbstractEndpointPathTest;
import dev.tachyonmcp.testkit.Mcp20251125Client;
import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Stateful server configured with {@code /api/mcp}: POST, the GET listen stream and session DELETE
 * are all served there.
 */
class CustomEndpointPathTest extends AbstractEndpointPathTest<Mcp20251125Client> {

    @Override
    protected SessionMode sessionMode() {
        return SessionMode.STATEFUL;
    }

    @Override
    protected String configuredEndpointPath() {
        return "/api/mcp";
    }

    @Override
    protected String requestPath() {
        return "/api/mcp";
    }

    @Override
    protected String protocolVersion() {
        return Mcp20251125Client.PROTOCOL_VERSION;
    }

    @Override
    protected @Nullable String openSession(Mcp20251125Client client) throws Exception {
        return client.initialize();
    }

    @Override
    protected Mcp20251125Client createTestClient(URI endpoint) {
        return new Mcp20251125Client(endpoint);
    }

    @Test
    void getListenStreamOpensOnEndpoint() throws Exception {
        try (var client = createTestClient()) {
            var sessionId = client.initialize();

            try (var subscriber = client.openGetStream(sessionId, null)) {
                var primingEventId = subscriber.awaitFirstEventId(ofSeconds(5));

                assertThat(subscriber.rawResponse()).startsWith("HTTP/1.1 200 OK");
                assertThat(primingEventId).isNotBlank();
            }
        }
    }

    @Test
    void deleteTerminatesSessionOnEndpoint() throws Exception {
        try (var client = createTestClient()) {
            var sessionId = client.initialize();
            assertThat(engine().getSession(sessionId))
                    .isPresent()
                    .map(Session::state)
                    .hasValue(SessionState.ACTIVE);

            var deleteResponse = client.delete(sessionId);
            var pingAfterDelete = client.ping(sessionId, 1);

            assertThat(deleteResponse.statusCode()).as(deleteResponse.body()).isEqualTo(200);
            assertThat(engine().getSession(sessionId)).isEmpty();
            // MCP Streamable HTTP: a terminated session ID returns HTTP 404.
            assertThat(pingAfterDelete.statusCode()).isEqualTo(404);
        }
    }
}
