/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2025_11_25;

import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * SSE polling behaviour: retry field, priming events, and event ID sequencing.
 *
 * <p>Pattern: POST to send requests, GET to receive SSE events.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SsePollingTest extends AbstractStatefulMcpE2eTest {

    @Test
    @DisplayName("[SEP-1699] SSE stream sets the retry interval for client reconnects")
    void getSseStreamIncludesRetryField() throws Exception {
        var sessionId = initializeSession();
        try (var client = createTestClient();
                var subscriber = client.openGetStream(sessionId, null)) {
            var raw = subscriber.awaitRawResponse(body -> body.contains("retry: 3000"), ofSeconds(2));
            assertThat(raw).contains("retry: 3000");
            assertThat(raw).contains("X-Accel-Buffering: no");
            assertThat(raw)
                    .as("SSE response must signal Connection: close so the client does not pool the socket")
                    .containsIgnoringCase("connection: close");
        }
    }

    @Test
    @DisplayName("[SEP-1699] SSE stream opens with a priming event carrying an event id")
    void getSseStreamSendsPrimingEventWithEventId() throws Exception {
        var sessionId = initializeSession();
        try (var client = createTestClient();
                var subscriber = client.openGetStream(sessionId, null)) {
            var first = subscriber.await(f -> f.id() != null, ofSeconds(2));
            assertThat(first.id()).as("Priming event should carry an event ID").isNotEmpty();
            assertThat(first.eventType()).as("Priming event type").isEqualTo("message");
            assertThat(first.data()).as("Priming event data should be empty").isEmpty();
        }
    }

    @Test
    void getSseStreamPrimingHasSequentialId() throws Exception {
        var sessionId = initializeSession();

        long firstId;
        try (var client = createTestClient();
                var subscriber = client.openGetStream(sessionId, null)) {
            firstId = Long.parseLong(subscriber.awaitFirstEventId(ofSeconds(2)));
        }

        long secondId;
        try (var client = createTestClient();
                var subscriber = client.openGetStream(sessionId, null)) {
            secondId = Long.parseLong(subscriber.awaitFirstEventId(ofSeconds(2)));
        }

        assertThat(secondId).isGreaterThan(firstId);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private String initializeSession() throws Exception {
        try (var client = createTestClient()) {
            return client.initialize();
        }
    }
}
