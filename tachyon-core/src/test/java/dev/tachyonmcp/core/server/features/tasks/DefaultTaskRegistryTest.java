/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.features.tasks;

import static dev.tachyonmcp.core.test.TestUtils.newEngine;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.server.domain.ProgressToken;
import dev.tachyonmcp.api.server.domain.TaskResult;
import dev.tachyonmcp.api.server.features.tasks.TaskConnector;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.api.server.features.tasks.TaskState;
import dev.tachyonmcp.core.server.config.TasksConfig;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DefaultTaskRegistryTest {

    private final ServerEngine server = newEngine(builder -> {});
    private final MutableClock clock = new MutableClock(Instant.parse("2026-08-27T07:00:00Z"));
    private final DefaultTaskRegistry registry = new DefaultTaskRegistry(
            server,
            TasksConfig.builder()
                    .enabled(true)
                    .connector(TaskConnector.builder()
                            .get((ctx, request) -> null)
                            .cancel((ctx, request) -> {})
                            .update((ctx, request) -> {})
                            .build())
                    .keepAlive(Duration.ofMinutes(5))
                    .pollInterval(Duration.ofSeconds(2))
                    .build(),
            clock);

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void publishAcceptsOnlyNewerRevisions() {
        var revisionOne = snapshot("task-1", TaskState.WORKING, 1);
        var revisionTwo = snapshot("task-1", TaskState.COMPLETED, 2);

        assertThat(registry.create(revisionOne, "owner", null).revision()).isEqualTo(1);
        var storedRevisionTwo = registry.publish(revisionTwo);
        assertThat(storedRevisionTwo.revision()).isEqualTo(2);
        assertThat(registry.publish(revisionOne)).isEqualTo(storedRevisionTwo);
        assertThat(registry.get("task-1")).isEqualTo(storedRevisionTwo);
    }

    @Test
    void onlyCreateCachesATaskAndItsOwnerNeverChanges() {
        var token = ProgressToken.of("tok");

        assertThat(registry.publish(snapshot("task-1", TaskState.WORKING, 1)).revision())
                .isEqualTo(1);
        assertThat(registry.get("task-1"))
                .as("publish never caches an unknown task")
                .isNull();

        var created = registry.create(snapshot("task-1", TaskState.WORKING, 1), "owner", token);
        assertThat(created).isNotNull();
        assertThat(registry.visibleTo("task-1", "owner")).isTrue();
        assertThat(registry.visibleTo("task-1", "intruder")).isFalse();

        assertThat(registry.create(snapshot("task-1", TaskState.COMPLETED, 2), "intruder", null))
                .as("another session cannot create a task id that is already owned")
                .isNull();
        assertThat(registry.create(snapshot("task-1", TaskState.COMPLETED, 2), null, null))
                .as("nor can a sessionless call")
                .isNull();
        assertThat(registry.get("task-1")).isEqualTo(created);

        var retried = registry.create(snapshot("task-1", TaskState.WORKING, 2), "owner", null);
        assertThat(retried).as("the owner's create is idempotent").isNotNull();
        assertThat(retried.revision()).isEqualTo(2);
        assertThat(registry.visibleTo("task-1", "owner")).isTrue();
        assertThat(registry.visibleTo("task-1", "intruder")).isFalse();
    }

    @Test
    void publishAppliesConfiguredPollIntervalWithoutChangingCallerSnapshot() {
        var snapshot = snapshot("task-1", TaskState.WORKING, 1);

        var published = registry.publish(snapshot);

        assertThat(snapshot.pollInterval()).isNull();
        assertThat(published.pollInterval()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void janitorEvictsTerminalProjectionButNeverTransitionsActiveWork() {
        registry.create(snapshot("active", TaskState.WORKING, 1), null, null);
        registry.create(snapshot("terminal", TaskState.COMPLETED, 1), null, null);
        registry.create(withTtl(snapshot("abandoned", TaskState.WORKING, 1), Duration.ofMinutes(6)), null, null);
        registry.create(withTtl(snapshot("long-lived", TaskState.WORKING, 1), Duration.ofHours(1)), null, null);

        clock.advance(Duration.ofMinutes(6));
        registry.runJanitorSweep();

        assertThat(registry.get("active")).isNotNull();
        assertThat(registry.get("active").status()).isEqualTo(TaskState.WORKING);
        assertThat(registry.get("terminal")).isNull();
        assertThat(registry.get("abandoned"))
                .as("ttl elapsed since createdAt: evicted whatever the status")
                .isNull();
        assertThat(registry.get("long-lived")).as("ttl not yet elapsed").isNotNull();
    }

    @Test
    void nonPositiveKeepAliveRetainsTerminalResults() {
        var terminal = snapshot("terminal", TaskState.COMPLETED, 1);
        var zeroRetention = new TaskEntry(terminal, null, null, Duration.ZERO, clock);
        var negativeRetention = new TaskEntry(terminal, null, null, Duration.ofSeconds(-1), clock);

        clock.advance(Duration.ofDays(1));

        assertThat(zeroRetention.isExpired()).isFalse();
        assertThat(negativeRetention.isExpired()).isFalse();
    }

    @Test
    void removeOnlyDropsProjection() {
        registry.create(snapshot("task-1", TaskState.WORKING, 1), null, null);

        assertThat(registry.remove("task-1")).isTrue();
        assertThat(registry.remove("task-1")).isFalse();
        assertThat(registry.get("task-1")).isNull();
    }

    private static TaskSnapshot withTtl(TaskSnapshot snapshot, Duration ttl) {
        return TaskSnapshot.builder().from(snapshot).ttl(ttl).build();
    }

    private TaskSnapshot snapshot(String taskId, TaskState status, long revision) {
        return TaskSnapshot.builder()
                .taskId(taskId)
                .status(status)
                .createdAt(clock.instant())
                .lastUpdatedAt(clock.instant())
                .result(status == TaskState.COMPLETED ? TaskResult.completed(Map.of()) : null)
                .revision(revision)
                .build();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
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
        public Instant instant() {
            return now;
        }
    }
}
