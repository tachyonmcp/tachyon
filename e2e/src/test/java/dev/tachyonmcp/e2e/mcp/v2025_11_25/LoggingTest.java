/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2025_11_25;

import static java.time.Duration.ofSeconds;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.testkit.SseFrame;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class LoggingTest extends AbstractStatefulMcpE2eTest {

    @Test
    void shouldReceiveLoggingNotificationAfterToolCall() throws Exception {
        try (var client = createTestClient()) {

            var sessionId = client.initialize();

            var setLevelBody =
                    // language=JSON
                    """
                    {"jsonrpc":"2.0","id":2,"method":"logging/setLevel","params":{"level":"debug"}}
                    """;
            var setLevelResponse = client.post(sessionId, setLevelBody);
            assertThatJson(setLevelResponse.body()).inPath("$.result").isObject();

            var toolBody =
                    // language=JSON
                    """
                    {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"echo","arguments":{"message":"hello"}}}
                    """;
            var toolResponse = client.post(sessionId, toolBody);

            assertThat(toolResponse.body())
                    .contains(
                            // language=json
                            """
                            {"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"hello"}]}}
                            """.trim());
        }
    }

    @Test
    @Timeout(30)
    void shouldBroadcastServerLogToListeningClient() throws Exception {
        // server.notifications().log(...) fans out to every active session, delivered on its GET listen stream
        try (var client = createTestClient()) {
            var sessionId = client.initialize();
            client.post(
                    sessionId,
                    // language=JSON
                    """
                    {"jsonrpc":"2.0","id":2,"method":"logging/setLevel","params":{"level":"debug"}}
                    """);

            var events = readListenStreamWhileBroadcasting(sessionId).toList();

            assertThat(events)
                    .as("the server-scoped error broadcast must arrive on the client's listen stream")
                    .anyMatch(data -> data.contains("notifications/message")
                            && data.contains("\"level\":\"error\"")
                            && data.contains("\"logger\":\"tachyon.svc\"")
                            && data.contains("server-broadcast"));
        }
    }

    /**
     * Opens a GET SSE listen stream and, once it is primed, triggers a server-scoped broadcast so
     * it rides that stream. Returns each SSE frame's {@code data:} payload as a stream, waiting for
     * the broadcast to arrive.
     */
    private Stream<String> readListenStreamWhileBroadcasting(String sessionId) {
        try (var client = createTestClient();
                var subscriber = client.openGetStream(sessionId, null)) {
            subscriber.awaitFirstEventId(ofSeconds(5));
            server.notifications().error("tachyon.svc", Map.of("event", "server-broadcast"));
            subscriber.await(f -> f.data().contains("server-broadcast"), ofSeconds(15));
            return subscriber.received(f -> true).stream().map(SseFrame::data);
        }
    }
}
