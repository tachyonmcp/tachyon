/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty.sse;

import static dev.tachyonmcp.core.test.TestUtils.newEngine;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.server.domain.RequestId;
import dev.tachyonmcp.core.server.config.SessionConfig;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.server.session.SessionEvent;
import dev.tachyonmcp.core.transport.netty.sse.SseManager.ResumeCursor;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class SseManagerTest {

    @Test
    void replayWithLastEventIdReturnsOnlyNewerEvents() {
        try (ServerEngine server = newEngine(b -> b.session(SessionConfig.Builder::enabled))) {
            server.createSession("sess_replay");
            for (long id = 1; id <= 5; id++) {
                server.appendEvent(new SessionEvent.ResponseEvent(
                        "sess_replay", RequestId.of(id), "{\"n\":" + id + "}", 1000L + id, id, null));
            }

            var manager = new SseManager(server);
            var sent = manager.missedEvents("sess_replay", cursor("3"));

            assertThat(sent).hasSize(2);
            assertThat(sent.get(0).id()).isEqualTo("4");
            assertThat(sent.get(1).id()).isEqualTo("5");
        }
    }

    @Test
    void replayWithFutureLastEventIdSkipsAll() {
        try (ServerEngine server = newEngine(b -> b.session(SessionConfig.Builder::enabled))) {
            server.createSession("sess_future");
            for (long id = 1; id <= 3; id++) {
                server.appendEvent(
                        new SessionEvent.ResponseEvent("sess_future", RequestId.of(id), "{}", 1000L + id, id, null));
            }

            var manager = new SseManager(server);
            var sent = manager.missedEvents("sess_future", cursor("9999"));

            assertThat(sent).isEmpty();
        }
    }

    @Test
    void replayWithZeroLastEventIdReturnsAllSseEvents() {
        try (ServerEngine server = newEngine(b -> b.session(SessionConfig.Builder::enabled))) {
            server.createSession("sess_zero");
            for (long id = 1; id <= 3; id++) {
                server.appendEvent(
                        new SessionEvent.ResponseEvent("sess_zero", RequestId.of(id), "{}", 1000L + id, id, null));
            }

            var manager = new SseManager(server);
            var sent = manager.missedEvents("sess_zero", cursor("0"));

            assertThat(sent).hasSize(3);
            assertThat(sent.get(0).id()).isEqualTo("1");
            assertThat(sent.get(1).id()).isEqualTo("2");
            assertThat(sent.get(2).id()).isEqualTo("3");
        }
    }

    @Test
    void replayMixedEventTypesSkipsNonSseEvents() {
        try (ServerEngine server = newEngine(b -> b.session(SessionConfig.Builder::enabled))) {
            server.createSession("sess_mixed");
            server.appendEvent(new SessionEvent.RequestEvent("sess_mixed", RequestId.of(1), "ping", "{}", 1000L));
            server.appendEvent(
                    new SessionEvent.ResponseEvent("sess_mixed", RequestId.of(1), "{\"pong\":true}", 1100L, 1L, null));
            server.appendEvent(new SessionEvent.CancelEvent("sess_mixed", RequestId.of(2), 1200L));
            server.appendEvent(
                    new SessionEvent.NotificationEvent("sess_mixed", "notifications/test", "{}", 1300L, 2L, null));

            var manager = new SseManager(server);
            var sent = manager.missedEvents("sess_mixed", cursor("0"));

            assertThat(sent).hasSize(2);
            assertThat(sent.get(0).id()).isEqualTo("1");
            assertThat(sent.get(0).data()).isEqualTo("{\"pong\":true}");
            assertThat(sent.get(1).id()).isEqualTo("2");
            assertThat(sent.get(1).data()).contains("notifications/test");
        }
    }

    @Test
    void parsesResumeCursor() {
        assertThat(ResumeCursor.parse("3")).isEqualTo(new ResumeCursor(3, null));
        assertThat(ResumeCursor.parse("5#12")).isEqualTo(new ResumeCursor(5, "12"));
        assertThat(ResumeCursor.parse("5#12").wireId()).isEqualTo("5#12");
        assertThat(ResumeCursor.parse("3").wireId()).isEqualTo("3");
        assertThat(ResumeCursor.parse(null)).isNull();
        assertThat(ResumeCursor.parse("")).isNull();
        assertThat(ResumeCursor.parse("not-a-number")).isNull();
        assertThat(ResumeCursor.parse("5#")).isNull();
        assertThat(ResumeCursor.parse("5#x\ndata: injected"))
                .as("the cursor is echoed as the priming id, so a non-numeric key must be rejected")
                .isNull();
    }

    private static ResumeCursor cursor(String lastEventId) {
        return Objects.requireNonNull(ResumeCursor.parse(lastEventId));
    }
}
