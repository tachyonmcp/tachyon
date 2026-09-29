/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2025_11_25;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.OutboundSseStreamMessageRouter;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * MCP 2025-11-25 Streamable HTTP resumability across two reconnects: the client drops a resumed
 * stream right after its priming event, before the replay reaches it, and resumes again with the
 * priming event's id. The priming event MUST carry the client's own cursor (including the POST
 * stream key), or the second resume starts past the backlog and never receives it.
 */
class SseResumeCursorTest extends AbstractStatefulMcpE2eTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Pattern PRIMING_ID = Pattern.compile("id: (\\d+#\\d+)", Pattern.MULTILINE);

    @Override
    protected void startDefaultServer() {
        startServerWith(s -> s.tools()
                .register(
                        b -> b.name("self-closing")
                                .description("Closes its SSE stream mid-call, then returns after a delay"),
                        (ctx, request) -> {
                            var stream = OutboundSseStreamMessageRouter.currentOutboundSseStream();
                            if (stream != null) {
                                stream.start();
                                stream.close();
                            }
                            try {
                                Thread.sleep(300);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return ToolResult.text("twice-resumed-payload");
                        }));
    }

    @Test
    void getStreamDroppedAfterPrimingStillReplaysBacklogOnNextResume() throws Exception {
        try (var client = createTestClient()) {
            var sessionId = client.initialize();

            String lastEventId;
            try (var subscriber = client.openGetStream(null)) {
                lastEventId = subscriber.awaitFirstEventId(TIMEOUT);
            }

            var session = engine().getLocalSession(sessionId).orElseThrow();
            engine().sendNotification(session, "notifications/message", Map.of("level", "info", "data", "missed-1"));
            engine().sendNotification(session, "notifications/message", Map.of("level", "info", "data", "missed-2"));

            // First resume: drop as soon as the priming event arrives.
            String primingId;
            try (var subscriber = client.openGetStream(lastEventId)) {
                primingId = subscriber.awaitFirstEventId(TIMEOUT);
            }
            assertThat(primingId)
                    .as("the resumed stream's priming event must keep the client's cursor")
                    .isEqualTo(lastEventId);

            // Second resume, from the id the client now holds.
            try (var subscriber = client.openGetStream(primingId)) {
                var received = subscriber.awaitRawResponse(
                        body -> body.contains("missed-1") && body.contains("missed-2"), TIMEOUT);
                assertThat(received.indexOf("missed-1"))
                        .as("backlog replayed in order")
                        .isLessThan(received.indexOf("missed-2"));
            }
        }
    }

    @Test
    void postStreamDroppedAfterPrimingKeepsStreamKeyAndDeliversResponse() throws Exception {
        try (var client = createTestClient()) {
            var sessionId = client.initialize();

            var post = client.post(sessionId, """
                    {"jsonrpc":"2.0","id":11,"method":"tools/call","params":{"name":"self-closing","arguments":{}}}
                    """);
            assertThat(post.headers().firstValue("Content-Type").orElse("")).contains("text/event-stream");
            var matcher = PRIMING_ID.matcher(post.body());
            assertThat(matcher.find())
                    .as("POST-SSE priming event id (<n>#<key>) in:\n%s", post.body())
                    .isTrue();
            var lastEventId = matcher.group(1);

            String primingId;
            try (var subscriber = client.openGetStream(lastEventId)) {
                primingId = subscriber.awaitFirstEventId(TIMEOUT);
            }
            assertThat(primingId)
                    .as("the resumed POST stream's priming event must keep <n>#<key>, or the next resume"
                            + " would switch to the GET stream")
                    .isEqualTo(lastEventId);

            try (var subscriber = client.openGetStream(primingId)) {
                var received = subscriber.awaitRawResponse(body -> body.contains("twice-resumed-payload"), TIMEOUT);
                assertThat(received)
                        .as("second resume of the POST stream must still deliver the final response")
                        .contains("\"id\":11")
                        .contains("twice-resumed-payload");
            }
        }
    }
}
