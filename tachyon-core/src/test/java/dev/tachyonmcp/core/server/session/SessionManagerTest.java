/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tachyonmcp.api.server.domain.LoggingLevel;
import dev.tachyonmcp.core.protocol.Protocols;
import dev.tachyonmcp.core.runtime.SessionState;
import dev.tachyonmcp.core.runtime.SseConnection;
import dev.tachyonmcp.core.runtime.SseEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class SessionManagerTest {

    private static final Instant NOW = Instant.parse("2026-09-09T12:00:00Z");

    @Test
    void persistsRuntimeStateAsImmutableSnapshot() {
        var store = new InMemorySessionStore();
        var manager = manager(store);
        var session = manager.createSession("s1");
        var protocol = Protocols.list().getFirst();

        session.protocol(protocol);
        session.enableExtension("io.tachyon/test");
        session.loggingLevel(LoggingLevel.WARNING);
        session.activate();
        session.touch();

        assertThat(store.find("s1")).hasValueSatisfying(snapshot -> {
            assertThat(snapshot.key().sessionId()).isEqualTo("s1");
            assertThat(snapshot.state()).isEqualTo(SessionState.ACTIVE);
            assertThat(snapshot.protocolVersion()).isEqualTo(protocol.versionString());
            assertThat(snapshot.enabledExtensionIds()).containsExactly("io.tachyon/test");
            assertThat(snapshot.loggingLevel()).isEqualTo(LoggingLevel.WARNING);
            assertThat(snapshot.expiresAt()).isEqualTo(NOW.plusSeconds(30));
            assertThat(snapshot.revision()).isPositive();
        });
    }

    @Test
    void hydratesLocalRuntimeFromSnapshot() {
        var store = new InMemorySessionStore();
        var protocol = Protocols.list().getFirst();
        var key = new SessionKey("s1", "generation");
        var created = store.create(key, NOW.plusSeconds(30));
        var persisted = new SessionSnapshot(
                key,
                SessionState.ACTIVE,
                protocol.versionString(),
                Set.of("io.tachyon/test"),
                LoggingLevel.ERROR,
                created.expiresAt(),
                created.revision() + 1);
        assertThat(store.compareAndSet(created, persisted)).isTrue();

        var session = manager(store).getSession("s1").orElseThrow();

        assertThat(session.state()).isEqualTo(SessionState.ACTIVE);
        assertThat(session.protocol()).isSameAs(protocol);
        assertThat(session.isExtensionEnabled("io.tachyon/test")).isTrue();
        assertThat(session.loggingLevel()).isEqualTo(LoggingLevel.ERROR);
    }

    @Test
    void expiredSnapshotDoesNotHydrate() {
        var store = new InMemorySessionStore();
        var key = new SessionKey("s1", "generation");
        store.create(key, NOW.minusSeconds(1));

        assertThat(manager(store).getSession("s1")).isEmpty();
        assertThat(store.find("s1")).isEmpty();
    }

    @Test
    void snapshotWithUnsupportedProtocolDoesNotHydrate() {
        final var store = new InMemorySessionStore();
        final var key = new SessionKey("s1", "generation");
        final var created = store.create(key, NOW.plusSeconds(30));
        final var incompatible = new SessionSnapshot(
                key,
                SessionState.ACTIVE,
                "unsupported-version",
                Set.of(),
                null,
                created.expiresAt(),
                created.revision() + 1);
        assertThat(store.compareAndSet(created, incompatible)).isTrue();

        assertThat(manager(store).getSession("s1")).isEmpty();
        assertThat(store.find("s1")).contains(incompatible);
    }

    @Test
    void touchRefreshesPersistedExpiryOnlyWhenHalfTheTtlHasElapsed() {
        final var store = new TrackingSessionStore();
        final var clock = new MutableClock(NOW);
        final var manager = new SessionManager(store, clock, Duration.ofSeconds(30));
        final var session = manager.createSession("s1");
        clock.resetInstantCount();

        session.touch();
        clock.advance(Duration.ofSeconds(14));
        session.touch();

        assertThat(store.touchCount()).isZero();
        assertThat(clock.instantCount()).isZero();

        clock.advance(Duration.ofSeconds(1));
        session.touch();
        session.touch();

        assertThat(store.touchCount()).isOne();
        assertThat(store.find("s1"))
                .hasValueSatisfying(snapshot -> assertThat(snapshot.expiresAt()).isEqualTo(NOW.plusSeconds(45)));
    }

    @Test
    void touchDelegatesDueSnapshotRefreshToExecutor() {
        final var store = new TrackingSessionStore();
        final var clock = new MutableClock(NOW);
        final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
        final var manager = new SessionManager(store, clock, Duration.ofSeconds(30), tasks::add);
        final var session = manager.createSession("s1");
        clock.advance(Duration.ofSeconds(15));

        session.touch();
        session.touch();

        assertThat(store.touchCount()).isZero();
        assertThat(tasks).hasSize(1);

        tasks.remove().run();

        assertThat(store.touchCount()).isOne();
    }

    @Test
    void touchEvictsOnlyLocalRuntimeAfterGenerationReplacement() {
        final var store = new TrackingSessionStore();
        final var clock = new MutableClock(NOW);
        final var manager = new SessionManager(store, clock, Duration.ofSeconds(30));
        final var session = manager.createSession("s1");
        final var replacementKey = new SessionKey("s1", "replacement");
        final var replacement = store.create(replacementKey, NOW.plusSeconds(60));
        clock.advance(Duration.ofSeconds(15));

        session.touch();

        assertThat(session.state()).isEqualTo(SessionState.CLOSED);
        assertThat(manager.allSessions()).isEmpty();
        assertThat(store.find("s1")).contains(replacement);
        assertThat(store.terminateCount()).isZero();
    }

    @Test
    void stateChangeUsesCachedSnapshotWithoutReadingStore() {
        final var store = new TrackingSessionStore();
        final var manager = manager(store);
        final var session = manager.createSession("s1");
        store.resetOperationCounts();

        session.activate();

        assertThat(store.findCount()).isZero();
        assertThat(store.compareAndSetCount()).isOne();
    }

    @Test
    void stateChangeUsesRevisionAdvancedByTouch() {
        final var store = new TrackingSessionStore();
        final var clock = new MutableClock(NOW);
        final var manager = new SessionManager(store, clock, Duration.ofSeconds(30));
        final var session = manager.createSession("s1");
        clock.advance(Duration.ofSeconds(15));
        session.touch();
        store.resetOperationCounts();

        session.loggingLevel(LoggingLevel.ERROR);

        assertThat(store.findCount()).isZero();
        assertThat(store.compareAndSetCount()).isOne();
        assertThat(store.find("s1"))
                .hasValueSatisfying(
                        snapshot -> assertThat(snapshot.loggingLevel()).isEqualTo(LoggingLevel.ERROR));
    }

    @Test
    void stateChangeEvictsLocalRuntimeAfterGenerationReplacement() {
        final var store = new TrackingSessionStore();
        final var manager = manager(store);
        final var session = manager.createSession("s1");
        final var replacement = store.create(new SessionKey("s1", "replacement"), NOW.plusSeconds(60));

        session.activate();

        assertThat(session.state()).isEqualTo(SessionState.CLOSED);
        assertThat(manager.allSessions()).isEmpty();
        assertThat(store.find("s1")).contains(replacement);
        assertThat(store.terminateCount()).isZero();
    }

    @Test
    void sameIdCreationsDoNotOverlapStoreAndKeepOneGeneration() throws Exception {
        final var store = new TrackingSessionStore();
        final var manager = manager(store);
        manager.createSession("s1");
        store.armCreateTracking();

        try (final var executor = Executors.newFixedThreadPool(2)) {
            final var first = executor.submit(() -> manager.createSession("s1"));
            assertThat(store.awaitFirstCreate()).isTrue();
            final var second = executor.submit(() -> {
                store.secondCreateAttempted();
                return manager.createSession("s1");
            });

            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }

        final var local = manager.getSession("s1").orElseThrow();
        final var persisted = store.find("s1").orElseThrow();
        assertThat(store.maxConcurrentCreates()).isOne();
        assertThat(local.key()).isEqualTo(persisted.key());
    }

    @Test
    void hashCollidingIdsDoNotSerializeStoreCreation() throws Exception {
        final var firstId = "Aa";
        final var secondId = "BB";
        assertThat(firstId.hashCode()).isEqualTo(secondId.hashCode());
        final var store = new TrackingSessionStore();
        final var manager = manager(store);
        store.armCreateTracking();

        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var first = executor.submit(() -> manager.createSession(firstId));
            assertThat(store.awaitFirstCreate()).isTrue();
            final var second = executor.submit(() -> {
                store.secondCreateAttempted();
                return manager.createSession(secondId);
            });

            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
        }

        assertThat(store.maxConcurrentCreates()).isEqualTo(2);
        assertThat(manager.getSession(firstId).orElseThrow().key())
                .isEqualTo(store.find(firstId).orElseThrow().key());
        assertThat(manager.getSession(secondId).orElseThrow().key())
                .isEqualTo(store.find(secondId).orElseThrow().key());
    }

    @Test
    void concurrentHydrationReadsStoreOnce() throws Exception {
        final var store = new TrackingSessionStore();
        store.create(new SessionKey("s1", "generation"), NOW.plusSeconds(30));
        store.armFindTracking();
        final var manager = manager(store);

        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var first = executor.submit(() -> manager.getSession("s1"));
            assertThat(store.awaitFirstFind()).isTrue();
            final var second = executor.submit(() -> {
                store.secondFindAttempted();
                return manager.getSession("s1");
            });

            assertThat(first.get(2, TimeUnit.SECONDS)).isPresent();
            assertThat(second.get(2, TimeUnit.SECONDS)).isPresent();
        }

        assertThat(store.findCount()).isOne();
        assertThat(store.maxConcurrentFinds()).isOne();
    }

    @Test
    void closeContinuesAfterConnectionFailureAndClosesStore() {
        final var store = new TrackingSessionStore();
        final var manager = manager(store);
        final var failedCloseAttempted = new AtomicBoolean();
        final var healthyCloseAttempted = new AtomicBoolean();
        manager.createSession("broken", new TestConnection(() -> {
            failedCloseAttempted.set(true);
            throw new IllegalStateException("boom");
        }));
        manager.createSession("healthy", new TestConnection(() -> healthyCloseAttempted.set(true)));

        manager.close();

        assertThat(failedCloseAttempted).isTrue();
        assertThat(healthyCloseAttempted).isTrue();
        assertThat(store.closeCount()).isOne();
    }

    @Test
    void sweepEvictsExpiredLocalSessionAndSnapshot() {
        var store = new InMemorySessionStore();
        var manager = manager(store);
        var session = manager.createSession("s1");
        var request = session.attachRequest();

        manager.sweep(-1);
        assertThat(session.state())
                .as("a request whose requestor is connected keeps its session")
                .isNotEqualTo(SessionState.CLOSED);

        request.disconnected();
        manager.sweep(-1);

        assertThat(session.state()).isEqualTo(SessionState.CLOSED);
        assertThat(store.find("s1")).isEmpty();
        assertThat(request.sessionClosed()).isCompleted();
    }

    @Test
    void sweepKeepsFreshSession() {
        var store = new InMemorySessionStore();
        var manager = manager(store);
        var session = manager.createSession("s1");
        session.activate();

        manager.sweep(Long.MAX_VALUE);

        assertThat(session.state()).isEqualTo(SessionState.ACTIVE);
        assertThat(manager.getSession("s1")).containsSame(session);
        assertThat(store.find("s1")).isPresent();
    }

    @Test
    void removeSessionClosesRuntimeWhenSnapshotTerminationFails() {
        final var store = new TrackingSessionStore();
        final var manager = manager(store);
        final var connectionClosed = new AtomicBoolean();
        final var session = manager.createSession("s1", new TestConnection(() -> connectionClosed.set(true)));
        final var request = session.attachRequest();
        store.armTerminateFailure();

        assertThatThrownBy(() -> manager.removeSession("s1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("terminate failed");

        assertThat(store.terminateCount()).isOne();
        assertThat(connectionClosed).isTrue();
        assertThat(session.state()).isEqualTo(SessionState.CLOSED);
        assertThat(request.sessionClosed()).isCompleted();
        assertThat(manager.getLocalSession("s1")).isEmpty();
        assertThat(manager.allSessions()).isEmpty();
    }

    @Test
    void sweepClosesRuntimeWhenSnapshotTerminationFails() {
        final var store = new TrackingSessionStore();
        final var manager = manager(store);
        final var connectionClosed = new AtomicBoolean();
        final var session = manager.createSession("s1", new TestConnection(() -> connectionClosed.set(true)));
        final var request = session.attachRequest();
        request.disconnected();
        store.armTerminateFailure();

        manager.sweep(-1);

        assertThat(store.terminateCount()).isOne();
        assertThat(connectionClosed).isTrue();
        assertThat(session.state()).isEqualTo(SessionState.CLOSED);
        assertThat(request.sessionClosed()).isCompleted();
        assertThat(manager.getLocalSession("s1")).isEmpty();
        assertThat(manager.allSessions()).isEmpty();
    }

    @Test
    void removeSessionKeepsTerminationFailureWhenConnectionCloseAlsoFails() {
        final var store = new TrackingSessionStore();
        final var manager = manager(store);
        final var closeFailure = new IllegalStateException("close failed");
        final var session = manager.createSession("s1", new TestConnection(() -> {
            throw closeFailure;
        }));
        final var request = session.attachRequest();
        store.armTerminateFailure();

        assertThatThrownBy(() -> manager.removeSession("s1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("terminate failed")
                .hasSuppressedException(closeFailure);

        assertThat(session.state()).isEqualTo(SessionState.CLOSED);
        assertThat(request.sessionClosed()).isCompleted();
        assertThat(manager.getLocalSession("s1")).isEmpty();
    }

    @Test
    void removeSessionThrowsConnectionCloseFailureWhenTerminationSucceeds() {
        final var store = new TrackingSessionStore();
        final var manager = manager(store);
        final var closeFailure = new IllegalStateException("close failed");
        final var session = manager.createSession("s1", new TestConnection(() -> {
            throw closeFailure;
        }));
        final var request = session.attachRequest();

        assertThatThrownBy(() -> manager.removeSession("s1")).isSameAs(closeFailure);

        assertThat(store.terminateCount()).isOne();
        assertThat(session.state()).isEqualTo(SessionState.CLOSED);
        assertThat(request.sessionClosed()).isCompleted();
        assertThat(manager.getLocalSession("s1")).isEmpty();
    }

    @Test
    void removeSessionDoesNotLetConcurrentLookupRestoreTerminatingSnapshot() throws Exception {
        assertLookupDuringTerminationFindsNothing(manager -> manager.removeSession("s1"));
    }

    @Test
    void sweepDoesNotLetConcurrentLookupRestoreTerminatingSnapshot() throws Exception {
        assertLookupDuringTerminationFindsNothing(manager -> manager.sweep(-1));
    }

    private static void assertLookupDuringTerminationFindsNothing(Consumer<SessionManager> termination)
            throws Exception {
        final var store = new TrackingSessionStore();
        final var manager = manager(store);
        final var session = manager.createSession("s1");
        store.armTerminateHold();

        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var terminating = executor.submit(() -> termination.accept(manager));
            assertThat(store.awaitTerminate()).isTrue();
            final var lookup = executor.submit(() -> manager.getSession("s1"));

            terminating.get(2, TimeUnit.SECONDS);
            assertThat(lookup.get(2, TimeUnit.SECONDS))
                    .as("a lookup racing termination must not restore the snapshot being deleted")
                    .isEmpty();
        }

        assertThat(session.state()).isEqualTo(SessionState.CLOSED);
        assertThat(store.terminateCount()).isOne();
        assertThat(store.find("s1")).isEmpty();
        assertThat(manager.getLocalSession("s1")).isEmpty();
        assertThat(manager.allSessions()).isEmpty();
    }

    private static SessionManager manager(SessionStore store) {
        return new SessionManager(store, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(30));
    }

    private static final class TrackingSessionStore implements SessionStore {
        private final InMemorySessionStore delegate = new InMemorySessionStore();
        private final AtomicInteger touchCount = new AtomicInteger();
        private final AtomicInteger terminateCount = new AtomicInteger();
        private final AtomicInteger findCount = new AtomicInteger();
        private final AtomicInteger compareAndSetCount = new AtomicInteger();
        private final AtomicInteger closeCount = new AtomicInteger();
        private final AtomicInteger concurrentCreates = new AtomicInteger();
        private final AtomicInteger maxConcurrentCreates = new AtomicInteger();
        private final AtomicInteger concurrentFinds = new AtomicInteger();
        private final AtomicInteger maxConcurrentFinds = new AtomicInteger();
        private final CountDownLatch firstCreateEntered = new CountDownLatch(1);
        private final CountDownLatch secondCreateAttempted = new CountDownLatch(1);
        private final CountDownLatch overlappingCreateEntered = new CountDownLatch(1);
        private final CountDownLatch firstFindEntered = new CountDownLatch(1);
        private final CountDownLatch secondFindAttempted = new CountDownLatch(1);
        private final CountDownLatch overlappingFindEntered = new CountDownLatch(1);
        private volatile boolean trackCreates;
        private volatile boolean trackFinds;
        private volatile boolean failTerminate;
        private volatile boolean holdTerminate;
        private final CountDownLatch terminateEntered = new CountDownLatch(1);
        private final CountDownLatch findDuringTerminate = new CountDownLatch(1);

        @Override
        public SessionSnapshot create(SessionKey key, Instant expiresAt) {
            if (!trackCreates) {
                return delegate.create(key, expiresAt);
            }
            final var concurrent = concurrentCreates.incrementAndGet();
            maxConcurrentCreates.accumulateAndGet(concurrent, Math::max);
            try {
                if (concurrent == 1) {
                    firstCreateEntered.countDown();
                    await(secondCreateAttempted);
                    await(overlappingCreateEntered, 2, TimeUnit.SECONDS);
                } else {
                    overlappingCreateEntered.countDown();
                }
                return delegate.create(key, expiresAt);
            } finally {
                concurrentCreates.decrementAndGet();
            }
        }

        @Override
        public Optional<SessionSnapshot> find(String sessionId) {
            findCount.incrementAndGet();
            if (holdTerminate) {
                findDuringTerminate.countDown();
            }
            if (!trackFinds) {
                return delegate.find(sessionId);
            }
            final var concurrent = concurrentFinds.incrementAndGet();
            maxConcurrentFinds.accumulateAndGet(concurrent, Math::max);
            try {
                if (concurrent == 1) {
                    firstFindEntered.countDown();
                    await(secondFindAttempted);
                    await(overlappingFindEntered, 250, TimeUnit.MILLISECONDS);
                } else {
                    overlappingFindEntered.countDown();
                }
                return delegate.find(sessionId);
            } finally {
                concurrentFinds.decrementAndGet();
            }
        }

        @Override
        public boolean compareAndSet(SessionSnapshot expected, SessionSnapshot updated) {
            compareAndSetCount.incrementAndGet();
            return delegate.compareAndSet(expected, updated);
        }

        @Override
        public boolean touch(SessionKey key, Instant expiresAt) {
            touchCount.incrementAndGet();
            return delegate.touch(key, expiresAt);
        }

        @Override
        public boolean terminate(SessionKey key) {
            terminateCount.incrementAndGet();
            if (holdTerminate) {
                terminateEntered.countDown();
                await(findDuringTerminate, 250, TimeUnit.MILLISECONDS);
            }
            if (failTerminate) {
                throw new IllegalStateException("terminate failed");
            }
            return delegate.terminate(key);
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
            delegate.close();
        }

        void armCreateTracking() {
            trackCreates = true;
        }

        void armTerminateFailure() {
            failTerminate = true;
        }

        void armTerminateHold() {
            holdTerminate = true;
        }

        boolean awaitTerminate() throws InterruptedException {
            return terminateEntered.await(2, TimeUnit.SECONDS);
        }

        void armFindTracking() {
            resetOperationCounts();
            trackFinds = true;
        }

        boolean awaitFirstCreate() throws InterruptedException {
            return firstCreateEntered.await(2, TimeUnit.SECONDS);
        }

        boolean awaitFirstFind() throws InterruptedException {
            return firstFindEntered.await(2, TimeUnit.SECONDS);
        }

        void secondCreateAttempted() {
            secondCreateAttempted.countDown();
        }

        void secondFindAttempted() {
            secondFindAttempted.countDown();
        }

        void resetOperationCounts() {
            findCount.set(0);
            compareAndSetCount.set(0);
        }

        int touchCount() {
            return touchCount.get();
        }

        int terminateCount() {
            return terminateCount.get();
        }

        int findCount() {
            return findCount.get();
        }

        int compareAndSetCount() {
            return compareAndSetCount.get();
        }

        int closeCount() {
            return closeCount.get();
        }

        int maxConcurrentCreates() {
            return maxConcurrentCreates.get();
        }

        int maxConcurrentFinds() {
            return maxConcurrentFinds.get();
        }

        private static void await(CountDownLatch latch) {
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }

        private static void await(CountDownLatch latch, long timeout, TimeUnit unit) {
            try {
                latch.await(timeout, unit);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    private record TestConnection(Runnable onClose) implements SseConnection {

        @Override
        public boolean isWritable() {
            return true;
        }

        @Override
        public void send(SseEvent event) {}

        @Override
        public void close() {
            onClose.run();
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;
        private final AtomicInteger instantCount = new AtomicInteger();

        private MutableClock(Instant now) {
            this.now = new AtomicReference<>(now);
        }

        void advance(Duration duration) {
            now.updateAndGet(current -> current.plus(duration));
        }

        void resetInstantCount() {
            instantCount.set(0);
        }

        int instantCount() {
            return instantCount.get();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public long millis() {
            return now.get().toEpochMilli();
        }

        @Override
        public Instant instant() {
            instantCount.incrementAndGet();
            return now.get();
        }
    }
}
