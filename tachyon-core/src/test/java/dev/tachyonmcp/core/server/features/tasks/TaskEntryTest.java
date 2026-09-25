/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.features.tasks;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
