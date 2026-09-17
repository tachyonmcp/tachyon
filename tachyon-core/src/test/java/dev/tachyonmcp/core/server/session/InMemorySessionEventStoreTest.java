/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.session;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.server.domain.RequestId;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class InMemorySessionEventStoreTest {

    private static SessionEvent requestEvent(String sessionId, int i) {
        return new SessionEvent.RequestEvent(sessionId, RequestId.of(i), "ping", "{}", System.currentTimeMillis());
    }

    private static int intId(SessionEvent.RequestEvent event) {
        return ((RequestId.NumericValue) event.requestId()).value().intValue();
    }

    @Test
    void appendAndReplay() {
        List<SessionEvent> s2;
        try (var store = new InMemorySessionEventStore()) {
            store.append(requestEvent("s1", 1));
            store.append(requestEvent("s1", 2));
            store.append(requestEvent("s2", 3));

            var s1 = store.replay("s1", -1);
            assertThat(s1).hasSize(2).allMatch(e -> Objects.equals(e.sessionId(), "s1"));

            s2 = store.replay("s2", -1);
        }
        assertThat(s2).hasSize(1).allMatch(e -> Objects.equals(e.sessionId(), "s2"));
    }

    @Test
    void replayFromOffset() {
        try (var store = new InMemorySessionEventStore()) {
            for (int i = 0; i < 10; i++) {
                store.append(requestEvent("s1", i));
            }
            // lastSeq is exclusive: replay(5) resumes AFTER event 5 → events 6..9.
            var result = store.replay("s1", 5);
            assertThat(result).hasSize(4);
            var first = (SessionEvent.RequestEvent) result.getFirst();
            assertThat(intId(first)).isEqualTo(6);
        }
    }

    @Test
    void closeClears() {
        var store = new InMemorySessionEventStore();
        store.append(requestEvent("s1", 1));
        store.close();
        assertThat(store.replay("s1", -1)).isEmpty();
    }

    @Test
    void concurrentAppendCorrectness() throws Exception {
        // maxEventsPerSession == maxEvents: isolates this test to the global cap alone, since a
        // per-session cap smaller than the global one would starve sessions before the global
        // window fills, which is a different behaviour covered by the per-session cap tests below.
        try (var store = new InMemorySessionEventStore(
                0, InMemorySessionEventStore.DEFAULT_MAX_EVENTS, InMemorySessionEventStore.DEFAULT_MAX_EVENTS)) {
            int threads = 8;
            int eventsPerThread = 10_000;
            var latch = new CountDownLatch(1);
            var totalAppended = new AtomicLong(0);

            try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int t = 0; t < threads; t++) {
                    final int tid = t;
                    exec.submit(() -> {
                        try {
                            latch.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        for (int i = 0; i < eventsPerThread; i++) {
                            store.append(requestEvent("sess_" + tid, i));
                            totalAppended.incrementAndGet();
                        }
                    });
                }
                latch.countDown();
                exec.shutdown();
                exec.awaitTermination(30, TimeUnit.SECONDS);
            }

            assertThat(totalAppended.get()).isEqualTo((long) threads * eventsPerThread);

            // The log is bounded: 80k appends against a 10k window retain exactly the newest window.
            // Concurrent correctness for a bounded log means: nothing torn or duplicated, and each
            // session's surviving events are an ordered suffix of what that session appended.
            // A session may retain NOTHING (it finished early and later appends evicted its whole
            // tail) — but if anything survives, eviction is oldest-first, so the survivors must be
            // the contiguous tail ending at that session's final event.
            var totalRetained = 0;
            for (int t = 0; t < threads; t++) {
                var result = store.replay("sess_" + t, -1);
                totalRetained += result.size();
                if (result.isEmpty()) {
                    continue;
                }
                var ids = result.stream()
                        .map(e -> intId((SessionEvent.RequestEvent) e))
                        .toList();
                assertThat(ids.getLast()).isEqualTo(eventsPerThread - 1);
                for (int i = 1; i < ids.size(); i++) {
                    assertThat(ids.get(i)).isEqualTo(ids.get(i - 1) + 1);
                }
            }
            assertThat(totalRetained).isEqualTo(store.maxEvents);
        }
    }

    @Test
    void trimDropsOldestAndKeepsCursorSemantics() {
        // maxEventsPerSession == maxEvents: this test floods a single session and checks the
        // global cap's trim/cursor behaviour, not the per-session cap (covered separately below).
        try (var store = new InMemorySessionEventStore(
                0, InMemorySessionEventStore.DEFAULT_MAX_EVENTS, InMemorySessionEventStore.DEFAULT_MAX_EVENTS)) {
            int overflow = 100;
            int total = store.maxEvents + overflow;
            for (int i = 0; i < total; i++) {
                store.append(requestEvent("s1", i));
            }

            var all = store.replay("s1", -1);
            assertThat(all).hasSize(store.maxEventsPerSession);
            var first = (SessionEvent.RequestEvent) all.getFirst();
            assertThat(intId(first)).isEqualTo(overflow);

            // Global-index cursor from inside the window is not misaligned by the trim: replay
            // resumes AFTER lastSeq (exclusive, matching drain).
            var tail = store.replay("s1", total - 5);
            assertThat(tail).hasSize(4);
            var tailFirst = (SessionEvent.RequestEvent) tail.getFirst();
            assertThat(intId(tailFirst)).isEqualTo(total - 5 + 1);
        }
    }

    @Test
    void perSessionCapProtectsOtherSessionsFromAHoggingSession() {
        try (var store = new InMemorySessionEventStore(0, 100, 5)) {
            for (int i = 0; i < 3; i++) {
                store.append(requestEvent("quiet", i));
            }
            for (int i = 0; i < 20; i++) {
                store.append(requestEvent("hog", i));
            }

            var hogEvents = store.replay("hog", -1);
            assertThat(hogEvents).hasSize(5);
            var hogFirst = (SessionEvent.RequestEvent) hogEvents.getFirst();
            assertThat(intId(hogFirst)).isEqualTo(15);

            var quietEvents = store.replay("quiet", -1);
            assertThat(quietEvents).hasSize(3);
        }
    }

    @Test
    void globalCapStillEvictsOldestAcrossSessionsWithinTheirOwnCap() {
        try (var store = new InMemorySessionEventStore(0, 60, 20)) {
            for (int i = 0; i < 20; i++) {
                for (int s = 0; s < 5; s++) {
                    store.append(requestEvent("s" + s, i));
                }
            }

            int totalRetained = 0;
            for (int s = 0; s < 5; s++) {
                totalRetained += store.replay("s" + s, -1).size();
            }
            assertThat(totalRetained).isEqualTo(60);
        }
    }

    @Test
    void sessionEmptiedByGlobalEvictionCanAppendAgainCleanly() {
        try (var store = new InMemorySessionEventStore(0, 5, 1000)) {
            store.append(requestEvent("s1", 0));
            store.append(requestEvent("s1", 1));
            for (int i = 0; i < 10; i++) {
                store.append(requestEvent("s2", i));
            }
            // s1's two events are the globally oldest and get fully evicted by s2's flood.
            assertThat(store.replay("s1", -1)).isEmpty();

            store.append(requestEvent("s1", 42));
            var result = store.replay("s1", -1);
            assertThat(result).hasSize(1);
            var only = (SessionEvent.RequestEvent) result.getFirst();
            assertThat(intId(only)).isEqualTo(42);
        }
    }
}
