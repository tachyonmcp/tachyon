/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.features.tasks;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.server.domain.TaskResult;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.api.server.features.tasks.TaskState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class TaskEntryTest {

    private static final Instant CREATED_AT = Instant.parse("2026-09-24T07:00:00Z");

    private final List<String> sent = new ArrayList<>();

    @Test
    void publisherThatLostTheRaceSendsNothingStale() {
        var entry = entry();
        entry.notifyIfNewer(this::record);

        // Publisher A applies revision 2, publisher B revision 3; B notifies first.
        entry.publish(working(2));
        entry.publish(working(3));
        entry.notifyIfNewer(this::record);
        entry.notifyIfNewer(this::record);

        assertThat(sent).containsExactly("1@owner", "3@owner");
    }

    @Test
    void ttlEvictsAnOwnedTaskOnlyOnceItIsTerminal() {
        var clock = Clock.fixed(CREATED_AT.plus(Duration.ofMinutes(2)), ZoneOffset.UTC);
        var elapsed = Duration.ofMinutes(1);
        var exactlyNow = Duration.ofMinutes(2);
        var pending = Duration.ofHours(1);

        assertThat(expired(null, withTtl(working(1), elapsed), clock))
                .as("ownerless, ttl elapsed")
                .isTrue();
        assertThat(expired(null, withTtl(working(1), exactlyNow), clock))
                .as("ownerless, ttl ends now")
                .isTrue();
        assertThat(expired(null, withTtl(working(1), pending), clock))
                .as("ownerless, ttl pending")
                .isFalse();
        assertThat(expired(null, working(1), clock)).as("ownerless, no ttl").isFalse();
        assertThat(expired("owner", withTtl(working(1), elapsed), clock))
                .as("owned and running: kept past its ttl so its owner check stays")
                .isFalse();
        assertThat(expired("owner", withTtl(completed(), elapsed), clock))
                .as("owned and terminal, ttl elapsed")
                .isTrue();
        assertThat(expired("owner", withTtl(completed(), pending), clock))
                .as("owned and terminal, ttl pending, keepAlive pending")
                .isFalse();
        assertThat(expired("owner", completed(), clock))
                .as("terminal, no ttl: keepAlive alone decides")
                .isFalse();
    }

    @Test
    void evictionRechecksExpiryAgainstTheCurrentSnapshot() {
        var clock = Clock.fixed(CREATED_AT.plus(Duration.ofMinutes(2)), ZoneOffset.UTC);
        var entry = new TaskEntry(withTtl(working(1), Duration.ofMinutes(1)), null, null, Duration.ZERO, clock);
        var removals = new AtomicInteger();

        // The sweep saw revision 1 expired; a renewal with a longer ttl lands before it evicts.
        assertThat(entry.isExpired()).isTrue();
        assertThat(entry.publish(withTtl(working(2), Duration.ofHours(1)))).isTrue();
        assertThat(entry.evictIfExpired(() -> removals.incrementAndGet() > 0))
                .as("a renewed task is kept")
                .isFalse();
        assertThat(removals).hasValue(0);

        assertThat(entry.publish(withTtl(working(3), Duration.ofMinutes(1)))).isTrue();
        assertThat(entry.evictIfExpired(() -> false))
                .as("expired, but the map no longer holds this entry")
                .isFalse();
        assertThat(entry.evictIfExpired(() -> removals.incrementAndGet() > 0)).isTrue();
        assertThat(removals).hasValue(1);
    }

    private static boolean expired(@Nullable String owner, TaskSnapshot snapshot, Clock clock) {
        return new TaskEntry(snapshot, owner, null, Duration.ofMinutes(5), clock).isExpired();
    }

    private static TaskSnapshot completed() {
        return TaskSnapshot.builder()
                .from(working(2))
                .status(TaskState.COMPLETED)
                .result(TaskResult.completed(Map.of()))
                .build();
    }

    private static TaskSnapshot withTtl(TaskSnapshot snapshot, Duration ttl) {
        return TaskSnapshot.builder().from(snapshot).ttl(ttl).build();
    }

    private void record(TaskSnapshot snapshot, @Nullable String session) {
        sent.add(snapshot.revision() + "@" + session);
    }

    private static TaskEntry entry() {
        return new TaskEntry(working(1), "owner", null, Duration.ofMinutes(5), Clock.systemUTC());
    }

    private static TaskSnapshot working(long revision) {
        return TaskSnapshot.working("task", CREATED_AT, revision);
    }
}
