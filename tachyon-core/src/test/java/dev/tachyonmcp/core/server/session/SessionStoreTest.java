/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The {@link SessionStore} default methods a store written against 1.0 inherits. */
class SessionStoreTest {

    private static final Instant CREATED_AT = Instant.parse("2026-09-09T12:00:00Z");

    @Test
    void defaultRevisionTerminateRemovesOnlyTheRevisionThatWasRead() {
        try (var store = new LegacyStore()) {
            var key = new SessionKey("s1", "g1");
            var read = store.create(key, CREATED_AT);
            assertThat(store.touch(key, CREATED_AT.plusSeconds(30))).isTrue();

            assertThat(store.terminate(key, read.revision()))
                    .as("a write after the read keeps the snapshot")
                    .isFalse();
            assertThat(store.terminate(new SessionKey("s1", "g0"), read.revision() + 1))
                    .as("another generation never matches")
                    .isFalse();
            assertThat(store.find("s1"))
                    .hasValueSatisfying(
                            current -> assertThat(current.revision()).isEqualTo(read.revision() + 1));

            assertThat(store.terminate(key, read.revision() + 1)).isTrue();
            assertThat(store.find("s1")).isEmpty();
            assertThat(store.terminate(key, read.revision() + 1)).isFalse();
        }
    }

    /** Implements only the 1.0 methods, so {@link SessionStore#terminate(SessionKey, long)} is the default. */
    private static final class LegacyStore implements SessionStore {
        private final InMemorySessionStore delegate = new InMemorySessionStore();

        @Override
        public SessionSnapshot create(SessionKey key, Instant expiresAt) {
            return delegate.create(key, expiresAt);
        }

        @Override
        public Optional<SessionSnapshot> find(String sessionId) {
            return delegate.find(sessionId);
        }

        @Override
        public boolean compareAndSet(SessionSnapshot expected, SessionSnapshot updated) {
            return delegate.compareAndSet(expected, updated);
        }

        @Override
        public boolean touch(SessionKey key, Instant expiresAt) {
            return delegate.touch(key, expiresAt);
        }

        @Override
        public boolean terminate(SessionKey key) {
            return delegate.terminate(key);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
