/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.session;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.core.runtime.SessionState;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class InMemorySessionStoreTest {

    private static final Instant CREATED_AT = Instant.parse("2026-09-09T12:00:00Z");

    @Test
    void replacementGenerationRejectsStaleWritesAndTermination() {
        try (var store = new InMemorySessionStore()) {
            var firstKey = new SessionKey("s1", "g1");
            var first = store.create(firstKey, CREATED_AT);
            var active = new SessionSnapshot(
                    first.key(),
                    SessionState.ACTIVE,
                    "2025-11-25",
                    first.enabledExtensionIds(),
                    null,
                    first.expiresAt(),
                    first.revision() + 1);

            assertThat(store.compareAndSet(first, active)).isTrue();
            assertThat(store.find("s1")).contains(active);

            var replacement = store.create(new SessionKey("s1", "g2"), Instant.parse("2026-09-09T13:00:00Z"));

            assertThat(store.compareAndSet(active, active)).isFalse();
            assertThat(store.touch(firstKey, Instant.parse("2026-09-09T14:00:00Z")))
                    .isFalse();
            assertThat(store.terminate(firstKey)).isFalse();
            assertThat(store.find("s1")).contains(replacement);
            assertThat(store.touch(replacement.key(), Instant.parse("2026-09-09T14:00:00Z")))
                    .isTrue();
            assertThat(store.find("s1")).hasValueSatisfying(touched -> {
                assertThat(touched.expiresAt()).isEqualTo(Instant.parse("2026-09-09T14:00:00Z"));
                assertThat(touched.revision()).isEqualTo(replacement.revision() + 1);
            });
            assertThat(store.terminate(replacement.key())).isTrue();
            assertThat(store.find("s1")).isEmpty();
        }
    }

    @Test
    void concurrentTouchesLandExactlyOnceEach() throws Exception {
        final int writers = 8;
        final int touchesPerWriter = 5_000;
        try (var store = new InMemorySessionStore()) {
            var key = new SessionKey("s1", "g1");
            var created = store.create(key, CREATED_AT);
            var start = new CountDownLatch(1);
            var rejected = new AtomicInteger();

            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var futures = IntStream.range(0, writers)
                        .mapToObj(writer -> executor.submit(() -> {
                            start.await();
                            var expiresAt = CREATED_AT.plusSeconds(writer + 1L);
                            for (int i = 0; i < touchesPerWriter; i++) {
                                if (!store.touch(key, expiresAt)) {
                                    rejected.incrementAndGet();
                                }
                            }
                            return null;
                        }))
                        .toList();
                start.countDown();
                for (var future : futures) {
                    future.get(30, TimeUnit.SECONDS);
                }
            }

            assertThat(rejected).hasValue(0);
            assertThat(store.find("s1")).hasValueSatisfying(snapshot -> {
                assertThat(snapshot.key()).isEqualTo(key);
                assertThat(snapshot.state()).isEqualTo(created.state());
                assertThat(snapshot.revision()).isEqualTo(created.revision() + (long) writers * touchesPerWriter);
                assertThat(snapshot.expiresAt()).isBetween(CREATED_AT.plusSeconds(1), CREATED_AT.plusSeconds(writers));
            });
            assertThat(store.terminate(key)).isTrue();
            assertThat(store.touch(key, CREATED_AT)).isFalse();
            assertThat(store.find("s1")).isEmpty();
        }
    }
}
