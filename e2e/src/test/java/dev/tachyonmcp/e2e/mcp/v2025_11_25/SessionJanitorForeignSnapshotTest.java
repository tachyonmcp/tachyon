/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2025_11_25;

import static java.time.Duration.ofMillis;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.server.session.InMemorySessionStore;
import dev.tachyonmcp.testkit.Mcp20251125Client;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Two nodes share one {@link InMemorySessionStore}. After traffic moves from node A to node B, A's
 * janitor evicts its idle runtime but must not terminate the snapshot B keeps alive (#446).
 */
class SessionJanitorForeignSnapshotTest {

    private static final Duration TTL = ofSeconds(2);
    private static final Duration JANITOR_INTERVAL = ofMillis(200);

    private final InMemorySessionStore store = new InMemorySessionStore();
    private TachyonServer nodeA;
    private TachyonServer nodeB;

    @BeforeEach
    void setUp() {
        nodeA = startNode();
        nodeB = startNode();
    }

    @AfterEach
    void tearDown() {
        nodeB.close();
        nodeA.close();
    }

    @Test
    void idleJanitorOnStaleNodeKeepsSnapshotUsedByAnotherNode() throws Exception {
        final var engineA = (ServerEngine) nodeA;
        final var requestIds = new AtomicInteger();
        try (var clientA = new Mcp20251125Client(nodeA.port());
                var clientB = new Mcp20251125Client(nodeB.port())) {
            final var sessionId = clientA.initialize();
            assertThat(engineA.getLocalSession(sessionId)).isPresent();

            await().atMost(ofSeconds(10)).pollInterval(ofMillis(200)).untilAsserted(() -> {
                assertThat(clientB.ping(sessionId, requestIds.incrementAndGet()).statusCode())
                        .isEqualTo(200);
                assertThat(engineA.getLocalSession(sessionId))
                        .as("node A's janitor evicts its idle runtime")
                        .isEmpty();
            });

            assertThat(store.find(sessionId))
                    .as("node A must not terminate the snapshot node B keeps alive")
                    .isPresent();

            await().during(TTL.multipliedBy(2))
                    .atMost(TTL.multipliedBy(3))
                    .pollInterval(TTL.dividedBy(5))
                    .untilAsserted(() -> assertThat(clientB.ping(sessionId, requestIds.incrementAndGet())
                                    .statusCode())
                            .as("node B keeps serving the session it took over")
                            .isEqualTo(200));
        }
    }

    private TachyonServer startNode() {
        final var server = TachyonServer.builder()
                .session(s -> s.sessionStore(store).sessionTtl(TTL).janitorInterval(JANITOR_INTERVAL))
                .network(n -> n.host("localhost").port(0))
                .build();
        server.start();
        return server;
    }
}
