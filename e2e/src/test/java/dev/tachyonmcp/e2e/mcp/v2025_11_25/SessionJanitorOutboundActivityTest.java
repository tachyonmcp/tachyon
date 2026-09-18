/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2025_11_25;

import static dev.tachyonmcp.testkit.JsonRpcResponseAssert.assertThat;
import static java.time.Duration.ofMillis;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.tachyonmcp.core.runtime.SessionState;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.testkit.Mcp20251125Client;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * E2E test for #75: a client that holds an SSE stream open but sends nothing must not be reaped by
 * the session janitor. A controlled pair proves the heartbeat is the load-bearing signal — same
 * setup, heartbeats on vs off, opposite outcomes.
 *
 * <p>Timings sized for slow CI runners: TTL=2s, janitorInterval=500ms, heartbeatInterval=500ms.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionJanitorOutboundActivityTest {

    private static final Duration TTL = ofSeconds(2);
    private static final Duration JANITOR_INTERVAL = ofMillis(500);
    private static final Duration HEARTBEAT_INTERVAL = ofMillis(500);

    private TachyonServer serverHandle;
    private int port;

    @BeforeAll
    void beforeAll() {
        serverHandle = TachyonServer.builder()
                .session(s -> s.enabled().sessionTtl(TTL).janitorInterval(JANITOR_INTERVAL))
                .network(n -> n.host("localhost").port(0).heartbeatInterval(HEARTBEAT_INTERVAL))
                .build();
        serverHandle.start();
        port = serverHandle.port();
    }

    @AfterAll
    void afterAll() {
        serverHandle.close();
    }

    /** A silent GET SSE stream stays alive past the TTL because heartbeat writes touch the session. */
    @Test
    void silentListeningStreamStaysAlive() throws Exception {
        var sessionId = initializeAndActivate(port);
        try (var client = new Mcp20251125Client(port)) {
            var subscriber = client.openGetStream(sessionId, null);
            var opened = subscriber.awaitRawResponse(body -> body.contains("\r\n\r\n"), ofSeconds(5));
            assertThat(opened).contains("200 OK");

            // Well past the 2s TTL: the session must survive on heartbeats alone.
            await().atMost(ofSeconds(6)).pollInterval(ofMillis(200)).untilAsserted(() -> {
                var session = ((ServerEngine) serverHandle).getSession(sessionId);
                assertThat(session).isPresent();
                assertThat(session.get().state()).isEqualTo(SessionState.ACTIVE);
            });

            var ping = client.post(sessionId, """
                    {"jsonrpc":"2.0","id":1,"method":"ping"}
                    """);
            assertThat(ping.statusCode()).isEqualTo(200);
            assertThat(ping).isSuccess();
        }
    }

    /** Same setup with heartbeats disabled: the silent stream produces no writes, so it is reaped. */
    @Test
    void silentStreamReapedWhenHeartbeatsDisabled() throws Exception {
        var noHbServer = TachyonServer.builder()
                .session(s -> s.enabled().sessionTtl(TTL).janitorInterval(JANITOR_INTERVAL))
                .network(n -> n.host("localhost").port(0).heartbeatInterval(Duration.ZERO))
                .build();
        noHbServer.start();
        try (noHbServer) {
            var noHbPort = noHbServer.port();
            var sessionId = initializeAndActivate(noHbPort);
            try (var client = new Mcp20251125Client(noHbPort)) {
                var subscriber = client.openGetStream(sessionId, null);
                subscriber.awaitRawResponse(body -> body.contains("\r\n\r\n"), ofSeconds(5));

                await().atMost(ofSeconds(6))
                        .pollInterval(ofMillis(200))
                        .untilAsserted(() -> assertThat(((ServerEngine) noHbServer).getSession(sessionId))
                                .isEmpty());
            }
        }
    }

    // ---- helpers ----

    private String initializeAndActivate(int targetPort) throws Exception {
        try (var client = new Mcp20251125Client(targetPort)) {
            return client.initialize();
        }
    }
}
