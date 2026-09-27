/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.tasks.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.server.domain.TaskResult;
import dev.tachyonmcp.api.server.features.tasks.TaskConnector;
import dev.tachyonmcp.api.server.features.tasks.TaskNotFoundException;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.api.server.features.tasks.TaskState;
import dev.tachyonmcp.core.server.features.Pagination;
import dev.tachyonmcp.core.server.session.NoopInteractionContext;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class TaskEngineTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-08-27T07:00:00Z"));
    private final TaskEngine registry = new TaskEngine(
            new TaskEngineSettings(
                    TaskConnector.builder()
                            .get((ctx, request) -> null)
                            .cancel((ctx, request) -> {})
                            .update((ctx, request) -> {})
                            .build(),
                    Pagination.DEFAULT_PAGE_SIZE,
                    Duration.ofMinutes(5),
                    Duration.ofSeconds(2),
                    TaskEngineSettings.DEFAULT_RESULT_POLL_INTERVAL),
            clock);

    @Test
    void publishAcceptsOnlyNewerRevisions() {
        var revisionOne = snapshot("task-1", TaskState.WORKING, 1);
        var revisionTwo = snapshot("task-1", TaskState.COMPLETED, 2);

        assertThat(registry.publish(revisionOne, new TestRoute("caller")).revision())
                .isEqualTo(1);
        var storedRevisionTwo = registry.publish(revisionTwo);
        assertThat(storedRevisionTwo.revision()).isEqualTo(2);
        assertThat(registry.publish(revisionOne)).isEqualTo(storedRevisionTwo);
        assertThat(registry.get("task-1")).isEqualTo(storedRevisionTwo);
    }

    @Test
    void publishCachesAnUnknownTaskAndARoutedPublishNeverRefuses() {
        var route = new TestRoute("caller");

        var published = registry.publish(snapshot("task-1", TaskState.WORKING, 2));
        assertThat(registry.get("task-1")).as("publish caches an unknown task").isEqualTo(published);

        assertThat(registry.publish(snapshot("task-1", TaskState.WORKING, 1), route))
                .as("an older revision from the tool call keeps the cached one")
                .isEqualTo(published);
        assertThat(registry.publish(snapshot("task-1", TaskState.COMPLETED, 3), new TestRoute("other"))
                        .revision())
                .as("a colliding tool call is never refused; access is the connector's decision")
                .isEqualTo(3);
    }

    @Test
    void readableTaskIdsKeepOnlyWhatTheConnectorAnswersAndFailClosed() {
        var readable = snapshot("readable", TaskState.WORKING, 1);
        var guarded = new TaskEngine(
                new TaskEngineSettings(
                        TaskConnector.builder()
                                .get((ctx, request) -> switch (request.taskId()) {
                                    case "readable" -> readable;
                                    case "failing" -> throw new IOException("introspection endpoint unavailable");
                                    default -> throw new TaskNotFoundException(request.taskId());
                                })
                                .cancel((ctx, request) -> {})
                                .update((ctx, request) -> {})
                                .build(),
                        Pagination.DEFAULT_PAGE_SIZE,
                        TaskEngineSettings.DEFAULT_KEEP_ALIVE,
                        null,
                        TaskEngineSettings.DEFAULT_RESULT_POLL_INTERVAL),
                clock);

        assertThat(guarded.readableTaskIds(NoopInteractionContext.INSTANCE, Set.of("readable", "refused", "failing")))
                .as("refused and failing ids are left out: a connector outage never opens a stream")
                .containsExactly("readable");
        assertThat(guarded.get("readable"))
                .as("authorizing a listener caches nothing")
                .isNull();
        assertThat(guarded.readableTaskIds(NoopInteractionContext.INSTANCE, Set.of()))
                .isEmpty();
    }

    @Test
    void readableTaskIdsStopAtAnInterruptAndRestoreIt() {
        var asked = new ArrayList<String>();
        var guarded = new TaskEngine(
                new TaskEngineSettings(
                        TaskConnector.builder()
                                .get((ctx, request) -> {
                                    asked.add(request.taskId());
                                    throw new InterruptedException("executor shutting down");
                                })
                                .cancel((ctx, request) -> {})
                                .update((ctx, request) -> {})
                                .build(),
                        Pagination.DEFAULT_PAGE_SIZE,
                        TaskEngineSettings.DEFAULT_KEEP_ALIVE,
                        null,
                        TaskEngineSettings.DEFAULT_RESULT_POLL_INTERVAL),
                clock);

        try {
            assertThat(guarded.readableTaskIds(NoopInteractionContext.INSTANCE, Set.of("first", "second", "third")))
                    .as("an interrupted check allows nothing")
                    .isEmpty();
            assertThat(asked)
                    .as("the connector is not asked again after an interrupt")
                    .hasSize(1);
            assertThat(Thread.currentThread().isInterrupted())
                    .as("the interrupt is restored for the caller")
                    .isTrue();
        } finally {
            Thread.interrupted();
        }
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
        registry.publish(snapshot("active", TaskState.WORKING, 1), TaskRoute.NONE);
        registry.publish(snapshot("terminal", TaskState.COMPLETED, 1), TaskRoute.NONE);
        registry.publish(withTtl(snapshot("abandoned", TaskState.WORKING, 1), Duration.ofMinutes(6)), TaskRoute.NONE);
        registry.publish(withTtl(snapshot("long-lived", TaskState.WORKING, 1), Duration.ofHours(1)), TaskRoute.NONE);

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
        var zeroRetention = new TaskEntry(terminal, TaskRoute.NONE, Duration.ZERO, clock);
        var negativeRetention = new TaskEntry(terminal, TaskRoute.NONE, Duration.ofSeconds(-1), clock);

        clock.advance(Duration.ofDays(1));

        assertThat(zeroRetention.isExpired()).isFalse();
        assertThat(negativeRetention.isExpired()).isFalse();
    }

    @Test
    void listenersReceiveEachRevisionOnceAlongTheFirstRoute() {
        var events = new ArrayList<String>();
        registry.addListener(new TaskEvents() {
            @Override
            public void onStatus(TaskSnapshot snapshot, TaskRoute route) {
                events.add("status " + snapshot.taskId() + "@" + snapshot.revision() + " -> " + route);
            }

            @Override
            public void onProgress(
                    String taskId, TaskRoute route, double progress, @Nullable Double total, @Nullable String message) {
                events.add("progress " + taskId + " " + progress + "/" + total + " " + message + " -> " + route);
            }
        });
        var changes = new ArrayList<String>();
        registry.onChange(() -> changes.add("changed"));
        var owner = new TestRoute("owner");

        registry.publish(snapshot("task-1", TaskState.WORKING, 1));
        registry.publish(snapshot("task-1", TaskState.WORKING, 2), owner);
        registry.publish(snapshot("task-1", TaskState.WORKING, 2), new TestRoute("intruder"));
        registry.publish(snapshot("task-1", TaskState.COMPLETED, 3), new TestRoute("intruder"));
        registry.reportProgress("task-1", 0.5, 1.0, "halfway");
        registry.reportProgress("unknown", 0.5, null, null);

        assertThat(events)
                .containsExactly(
                        "status task-1@1 -> TaskRoute.NONE",
                        "status task-1@2 -> " + owner,
                        "status task-1@3 -> " + owner,
                        "progress task-1 0.5/1.0 halfway -> " + owner);
        assertThat(changes).as("one change per accepted revision").hasSize(3);
    }

    @Test
    void removeOnlyDropsProjection() {
        registry.publish(snapshot("task-1", TaskState.WORKING, 1), TaskRoute.NONE);

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
