/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.transport.netty.McpChannelInitializer;
import dev.tachyonmcp.core.transport.netty.NettyIoEngine;
import dev.tachyonmcp.core.transport.netty.NettyServer;
import dev.tachyonmcp.core.transport.netty.NettyServerConfig;
import dev.tachyonmcp.testkit.Mcp20251125Client;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Verifies that a silent SSE stream is kept alive by periodic scheduler-driven comment heartbeats
 * rather than being reaped on idle. Uses a short heartbeat interval so beats arrive within the
 * test window, proving the scheduler runs independently of the reader-idle timeout.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SseHeartbeatTest {

    private static final Duration HEARTBEAT_INTERVAL = Duration.ofMillis(150);

    private ServerEngine server;
    private NettyServer nettyServer;
    private int port;

    @BeforeAll
    void startServer() {
        server = (ServerEngine) TachyonServer.builder()
                .session(s -> s.enabled())
                .network(n -> n.heartbeatInterval(HEARTBEAT_INTERVAL)
                        // Keep reader-idle longer than the test window so idle never fires
                        .readerIdleTimeout(Duration.ofMinutes(5)))
                .build();
        var config = new NettyServerConfig(
                "127.0.0.1",
                0,
                "/mcp",
                Duration.ofMinutes(5),
                Duration.ofMinutes(5),
                McpChannelInitializer.DEFAULT_MAX_CONTENT_LENGTH,
                NettyServerConfig.buildCorsConfig(null, false, false, null),
                null,
                null,
                false,
                NettyIoEngine.AUTO,
                null);
        nettyServer = new NettyServer(server, config);
        port = nettyServer.port();
    }

    @AfterAll
    void stopServer() {
        nettyServer.close();
        server.close();
    }

    @Test
    void idleSseStreamReceivesRepeatedHeartbeats() throws Exception {
        var sessionId = initializeSession();

        try (var client = new Mcp20251125Client(port);
                var subscriber = client.openGetStream(sessionId, null)) {
            // Poll until ~2 heartbeat comments have arrived, bounded by ~10 heartbeat intervals.
            var raw = subscriber.awaitRawResponse(
                    body -> countOccurrences(body, ":\r\n") >= 2, HEARTBEAT_INTERVAL.multipliedBy(10));

            assertThat(raw).as("stream must open as text/event-stream").contains("text/event-stream");
            assertThat(raw).contains("X-Accel-Buffering: no");
            assertThat(countOccurrences(raw, ":\r\n"))
                    .as("scheduler must drive repeated SSE comment heartbeats")
                    .isGreaterThanOrEqualTo(2);
        }
    }

    private String initializeSession() throws Exception {
        try (var client = new Mcp20251125Client(port)) {
            return client.initialize();
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        var count = 0;
        for (var i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}
