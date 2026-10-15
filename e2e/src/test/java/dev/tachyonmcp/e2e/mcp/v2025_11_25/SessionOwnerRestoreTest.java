/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2025_11_25;

import static dev.tachyonmcp.e2e.mcp.TestBearerTokens.ALICE;
import static dev.tachyonmcp.e2e.mcp.TestBearerTokens.BOB;
import static dev.tachyonmcp.e2e.mcp.v2025_11_25.RawSessionClient.whoAmI;
import static dev.tachyonmcp.testkit.JsonRpcResponseAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.server.session.InMemorySessionStore;
import dev.tachyonmcp.e2e.mcp.TestBearerTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Two nodes share one session store. A session created on node A and restored on node B keeps the
 * owner it was created with: its creator is served, nobody else is, and the restore never locks the
 * creator out.
 */
class SessionOwnerRestoreTest {

    private final TestBearerTokens tokens = new TestBearerTokens();
    private final InMemorySessionStore store = new InMemorySessionStore();
    private TachyonServer nodeA;
    private TachyonServer nodeB;

    @BeforeEach
    void startNodes() {
        nodeA = startNode();
        nodeB = startNode();
    }

    @AfterEach
    void stopNodes() {
        nodeB.close();
        nodeA.close();
    }

    @Test
    void restoredSessionServesItsCreatorAndNoOneElse() throws Exception {
        try (var clientA = new RawSessionClient(nodeA.port());
                var clientB = new RawSessionClient(nodeB.port())) {
            final var sessionId = clientA.initialize(ALICE);
            assertThat(((ServerEngine) nodeB).getLocalSession(sessionId)).isEmpty();

            final var bob = clientB.post(BOB, sessionId, whoAmI(2));
            final var alice = clientB.post(ALICE, sessionId, whoAmI(3));

            assertThat(bob.statusCode()).isEqualTo(404);
            assertThat(bob.body()).isEqualTo("Unknown session");
            assertThat(alice).isSuccess().hasId(3).hasTextContent("alice [tools:call, tools:read]");
            assertThat(((ServerEngine) nodeB).getLocalSession(sessionId)).isPresent();
            assertThat(tokens.toolCallers()).containsExactly("alice [tools:call, tools:read]");
        }
    }

    private TachyonServer startNode() {
        final var server = TachyonServer.builder()
                .capabilities(c -> c.tools())
                .security(s -> s.bearerToken(tokens))
                .session(s -> s.sessionStore(store))
                .network(n -> n.host("localhost").port(0))
                .build();
        tokens.registerWhoAmI(server);
        server.start();
        return server;
    }
}
