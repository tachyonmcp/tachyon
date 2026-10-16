/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tachyonmcp.api.server.security.SecurityContext;
import dev.tachyonmcp.core.server.security.Owner;
import dev.tachyonmcp.core.server.session.SessionKey;
import dev.tachyonmcp.core.server.session.SessionSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionTest {

    private Session session;
    private TestConnection connection;

    @BeforeEach
    void setUp() {
        connection = new TestConnection();
        session = new Session("sess_1", connection);
    }

    @Test
    void initialSessionState() {
        assertThat(session.id()).isEqualTo("sess_1");
        assertThat(session.state()).isEqualTo(SessionState.INITIALIZING);
        assertThat(session.backpressure()).isEqualTo(Backpressure.HOT);
        assertThat(session.cursor()).isEqualTo(-1L);
    }

    @Test
    void aSessionServesOnlyTheCallerThatCreatedIt() {
        final var alice = Owner.of(SecurityContext.authenticated(() -> "alice", List.of("tools:call")));
        final var bob = Owner.of(SecurityContext.authenticated(() -> "bob", List.of()));
        final var anonymous = Owner.anonymous();

        assertThat(session.isOwnedBy(anonymous)).isTrue();
        assertThat(session.isOwnedBy(alice)).isFalse();
        assertThat(ownedBy("alice").owner()).isEqualTo(alice);
        assertThat(ownedBy("alice").isOwnedBy(alice)).isTrue();
        assertThat(ownedBy("alice").isOwnedBy(bob)).isFalse();
        assertThat(ownedBy("alice").isOwnedBy(anonymous)).isFalse();
        assertThat(ownedBy("alice").isOwnedBy(Owner.of(SecurityContext.authenticated(() -> "alice", List.of()))))
                .as("scopes are not part of the owner")
                .isTrue();
    }

    private static Session ownedBy(String ownerKey) {
        return Session.fromSnapshot(
                new SessionSnapshot(
                        new SessionKey("sess_owned", "g1"),
                        ownerKey,
                        SessionState.INITIALIZING,
                        null,
                        Set.of(),
                        null,
                        Instant.MAX,
                        0),
                SseConnection.noop(),
                ignored -> {},
                ignored -> {});
    }

    @Test
    void activate() {
        assertThat(session.activate()).isTrue();
        assertThat(session.state()).isEqualTo(SessionState.ACTIVE);
    }

    @Test
    void closeSession() {
        session.activate();
        var inFlight = session.attachRequest();
        var done = session.attachRequest();
        done.completed();

        assertThat(session.close()).isTrue();
        assertThat(session.state()).isEqualTo(SessionState.CLOSED);
        // E12 fix: connection must become NOOP after close to prevent write-after-remove
        assertThat(session.connection()).isSameAs(SseConnection.noop());
        assertThat(session.connection().isWritable()).isFalse();
        assertThat(inFlight.sessionClosed()).isCompleted();
        assertThat(done.sessionClosed())
                .as("a completed request is no longer tracked")
                .isNotCompleted();
        assertThat(session.attachRequest().sessionClosed())
                .as("attached after the close")
                .isCompleted();
    }

    @Test
    void closeFromInitializingStateSetsNoop() {
        // session starts INITIALIZING — close() must still set NOOP connection
        assertThat(session.state()).isEqualTo(SessionState.INITIALIZING);
        assertThat(session.close()).isTrue();
        assertThat(session.state()).isEqualTo(SessionState.CLOSED);
        assertThat(session.connection()).isSameAs(SseConnection.noop());
    }

    @Test
    void closeIsIdempotentForConnection() {
        session.activate();
        session.close();
        session.close(); // second close must not throw and connection stays NOOP
        assertThat(session.connection()).isSameAs(SseConnection.noop());
        assertThat(session.state()).isEqualTo(SessionState.CLOSED);
    }

    @Test
    void backpressureHotWhenWritableAndEmpty() {
        connection.writable = true;
        assertThat(session.computeBackpressure()).isEqualTo(Backpressure.HOT);
    }

    @Test
    void backpressureColdWhenNotWritable() {
        connection.writable = false;
        assertThat(session.computeBackpressure()).isEqualTo(Backpressure.COLD);
    }

    @Test
    void cursor() {
        session.cursor(12345L);
        assertThat(session.cursor()).isEqualTo(12345L);
    }

    @Test
    void shouldThrottleWhenNotWritable() {
        connection.writable = true;
        assertThat(session.shouldThrottle()).isFalse();
        connection.writable = false;
        assertThat(session.shouldThrottle()).isTrue();
    }

    @Test
    void sendDeliversWhenWritable() {
        connection.writable = true;
        var event = new SseEvent("1", "message", "{}");
        assertThat(session.send(event)).isTrue();
        assertThat(connection.sent).containsExactly(event);
        assertThat(session.backpressure()).isEqualTo(Backpressure.HOT);
    }

    @Test
    void sendLeavesSlowClientsToTheConnection() {
        connection.writable = false;
        var event = new SseEvent("1", "message", "{}");
        assertThat(session.send(event))
                .as("a slow client is buffered by the connection, never dropped from an open stream")
                .isTrue();
        assertThat(connection.sent).containsExactly(event);
        assertThat(connection.closed).isFalse();
        assertThat(session.shouldThrottle()).isTrue();
        assertThat(session.backpressure()).isEqualTo(Backpressure.COLD);
    }

    @Test
    void sendReturnsFalseAfterClose() {
        session.activate();
        session.close();
        assertThat(session.send(new SseEvent("1", "message", "{}"))).isFalse();
        assertThat(connection.sent).isEmpty();
    }

    @Test
    void throwsOnNullId() {
        assertThatThrownBy(() -> new Session(null, connection)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void throwsOnNullConnection() {
        assertThatThrownBy(() -> new Session("id", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void activateIsIdempotent() {
        assertThat(session.activate()).isTrue();
        assertThat(session.activate()).isFalse();
    }

    @Test
    void rejectsConnectionOnClosedSession() {
        session.activate();
        session.close();
        var newConn = new TestConnection();
        session.connection(newConn);
        assertThat(newConn.closed).isTrue();
        assertThat(session.connection()).isSameAs(SseConnection.noop());
    }

    private static class TestConnection implements SseConnection {

        volatile boolean writable = true;
        volatile boolean closed;
        final java.util.List<SseEvent> sent = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public boolean isWritable() {
            return writable;
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public void send(@NonNull SseEvent event) {
            sent.add(event);
        }
    }
}
