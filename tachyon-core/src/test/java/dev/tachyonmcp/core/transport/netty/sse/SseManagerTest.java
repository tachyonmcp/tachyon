/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty.sse;

import static dev.tachyonmcp.core.test.TestUtils.newEngine;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.server.domain.RequestId;
import dev.tachyonmcp.core.runtime.SseConnection;
import dev.tachyonmcp.core.runtime.SseEvent;
import dev.tachyonmcp.core.server.config.SessionConfig;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.server.session.SessionEvent;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class SseManagerTest {

    @Test
    void replayWithLastEventIdReturnsOnlyNewerEvents() {
        try (ServerEngine server = newEngine(b -> b.session(SessionConfig.Builder::enabled))) {
            var conn = new TrackingConnection();
            var session = server.createSession("sess_replay");
            session.connection(conn);
            for (long id = 1; id <= 5; id++) {
                server.appendEvent(new SessionEvent.ResponseEvent(
                        "sess_replay", RequestId.of(id), "{\"n\":" + id + "}", 1000L + id, id, null));
            }

            var manager = new SseManager(server);
            manager.replayEvents(session, "3");

            assertThat(conn.sent).hasSize(2);
            assertThat(conn.sent.get(0).id()).isEqualTo("4");
            assertThat(conn.sent.get(1).id()).isEqualTo("5");
        }
    }

    @Test
    void replayWithInvalidLastEventIdSkipsReplay() {
        try (ServerEngine server = newEngine(b -> b.session(SessionConfig.Builder::enabled))) {
            var conn = new TrackingConnection();
            var session = server.createSession("sess_bad");
            session.connection(conn);
            server.appendEvent(new SessionEvent.ResponseEvent("sess_bad", RequestId.of(1), "{}", 1000L, 1L, null));

            var manager = new SseManager(server);
            manager.replayEvents(session, "not-a-number");

            assertThat(conn.sent).isEmpty();
        }
    }

    @Test
    void replayWithFutureLastEventIdSkipsAll() {
        try (ServerEngine server = newEngine(b -> b.session(SessionConfig.Builder::enabled))) {
            var conn = new TrackingConnection();
            var session = server.createSession("sess_future");
            session.connection(conn);
            for (long id = 1; id <= 3; id++) {
                server.appendEvent(
                        new SessionEvent.ResponseEvent("sess_future", RequestId.of(id), "{}", 1000L + id, id, null));
            }

            var manager = new SseManager(server);
            manager.replayEvents(session, "9999");

            assertThat(conn.sent).isEmpty();
        }
    }

    @Test
    void replayWithZeroLastEventIdReturnsAllSseEvents() {
        try (ServerEngine server = newEngine(b -> b.session(SessionConfig.Builder::enabled))) {
            var conn = new TrackingConnection();
            var session = server.createSession("sess_zero");
            session.connection(conn);
            for (long id = 1; id <= 3; id++) {
                server.appendEvent(
                        new SessionEvent.ResponseEvent("sess_zero", RequestId.of(id), "{}", 1000L + id, id, null));
            }

            var manager = new SseManager(server);
            manager.replayEvents(session, "0");

            assertThat(conn.sent).hasSize(3);
            assertThat(conn.sent.get(0).id()).isEqualTo("1");
            assertThat(conn.sent.get(1).id()).isEqualTo("2");
            assertThat(conn.sent.get(2).id()).isEqualTo("3");
        }
    }

    @Test
    void replayMixedEventTypesSkipsNonSseEvents() {
        try (ServerEngine server = newEngine(b -> b.session(SessionConfig.Builder::enabled))) {
            var conn = new TrackingConnection();
            var session = server.createSession("sess_mixed");
            session.connection(conn);
            server.appendEvent(new SessionEvent.RequestEvent("sess_mixed", RequestId.of(1), "ping", "{}", 1000L));
            server.appendEvent(
                    new SessionEvent.ResponseEvent("sess_mixed", RequestId.of(1), "{\"pong\":true}", 1100L, 1L, null));
            server.appendEvent(new SessionEvent.CancelEvent("sess_mixed", RequestId.of(2), 1200L));
            server.appendEvent(
                    new SessionEvent.NotificationEvent("sess_mixed", "notifications/test", "{}", 1300L, 2L, null));

            var manager = new SseManager(server);
            manager.replayEvents(session, "0");

            assertThat(conn.sent).hasSize(2);
            assertThat(conn.sent.get(0).id()).isEqualTo("1");
            assertThat(conn.sent.get(0).data()).isEqualTo("{\"pong\":true}");
            assertThat(conn.sent.get(1).id()).isEqualTo("2");
            assertThat(conn.sent.get(1).data()).contains("notifications/test");
        }
    }

    private static class TrackingConnection implements SseConnection {

        final ArrayList<SseEvent> sent = new ArrayList<>();

        @Override
        public boolean isWritable() {
            return true;
        }

        @Override
        public void send(SseEvent event) {
            sent.add(event);
        }
    }
}
