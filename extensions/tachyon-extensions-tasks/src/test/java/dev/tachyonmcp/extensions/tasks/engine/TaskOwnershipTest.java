/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.tasks.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.domain.TaskResult;
import dev.tachyonmcp.api.server.features.PaginatedResult;
import dev.tachyonmcp.api.server.features.tasks.TaskConnector;
import dev.tachyonmcp.api.server.features.tasks.TaskGetRequest;
import dev.tachyonmcp.api.server.features.tasks.TaskListRequest;
import dev.tachyonmcp.api.server.features.tasks.TaskNotFoundException;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.api.server.features.tasks.TaskState;
import dev.tachyonmcp.api.server.features.tasks.TaskUpdateRequest;
import dev.tachyonmcp.api.server.security.SecurityContext;
import dev.tachyonmcp.core.server.features.Pagination;
import dev.tachyonmcp.core.server.security.Owner;
import dev.tachyonmcp.core.server.session.NoopInteractionContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * A task belongs to the caller that created it. The engine's connector answers anyone else as it
 * answers an unknown id, before the real connector runs, for as long as the task can be reached.
 */
class TaskOwnershipTest {

    private static final Duration KEEP_ALIVE = Duration.ofMinutes(5);
    private static final Duration TTL = Duration.ofHours(1);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-15T07:00:00Z"));
    private final List<String> connectorCalls = new ArrayList<>();
    private final List<String> pushed = new ArrayList<>();
    private final InteractionContext alice = caller("alice");
    private final InteractionContext bob = caller("bob");
    private final InteractionContext anonymous = caller(null);
    private final TaskEngine engine = new TaskEngine(
            new TaskEngineSettings(
                    recordingConnector(),
                    Pagination.DEFAULT_PAGE_SIZE,
                    KEEP_ALIVE,
                    null,
                    TaskEngineSettings.DEFAULT_RESULT_POLL_INTERVAL),
            clock);

    TaskOwnershipTest() {
        engine.addListener(new TaskEvents() {
            @Override
            public void onStatus(TaskSnapshot snapshot, TaskRoute route) {
                pushed.add(snapshot.taskId() + "@" + snapshot.revision() + " -> " + route);
            }

            @Override
            public void onProgress(
                    String taskId,
                    TaskRoute route,
                    double progress,
                    @Nullable Double total,
                    @Nullable String message) {}
        });
    }

    @Test
    void anotherCallerNeverReachesTheRealConnectorForAnOwnedTask() throws Exception {
        engine.publishAs(alice, task("owned", TaskState.WORKING, 1, null), new TestRoute("alice-session"));
        engine.publish(task("unowned", TaskState.WORKING, 1, null));
        var connector = engine.connector();

        assertThatThrownBy(() -> connector.get().apply(bob, get("owned"))).isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> connector.cancel().apply(bob, cancel("owned")))
                .isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> connector.update().apply(bob, update("owned")))
                .isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> connector.awaitResult().apply(bob, awaitResult("owned")))
                .isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> connector.get().apply(anonymous, get("owned")))
                .isInstanceOf(TaskNotFoundException.class);
        assertThat(connectorCalls).as("nothing reached the real connector").isEmpty();

        assertThat(connector.get().apply(alice, get("owned")).taskId()).isEqualTo("owned");
        assertThat(connector.get().apply(bob, get("unowned")).taskId())
                .as("an unowned task is left to the connector")
                .isEqualTo("unowned");
        assertThat(connector.get().apply(alice, get("unknown-to-the-engine")).taskId())
                .isEqualTo("unknown-to-the-engine");
        assertThat(connectorCalls).containsExactly("get:owned", "get:unowned", "get:unknown-to-the-engine");
    }

    @Test
    void aListingLeavesOutTasksOwnedBySomeoneElse() throws Exception {
        engine.publishAs(alice, task("alices", TaskState.WORKING, 1, null), TaskRoute.NONE);
        engine.publishAs(bob, task("bobs", TaskState.WORKING, 1, null), TaskRoute.NONE);
        engine.publish(task("unowned", TaskState.WORKING, 1, null));
        var list = TaskListRequest.builder().limit(10).build();

        assertThat(ids(engine.connector().list().apply(alice, list))).containsExactly("alices", "unowned");
        assertThat(ids(engine.connector().list().apply(bob, list))).containsExactly("bobs", "unowned");
        assertThat(ids(engine.connector().list().apply(anonymous, list))).containsExactly("unowned");
    }

    @Test
    void theOwnerOfATaskNeverChanges() {
        var alicesSession = new TestRoute("alice-session");
        var bobsSession = new TestRoute("bob-session");
        engine.publishAs(alice, task("owned", TaskState.WORKING, 1, null), alicesSession);
        engine.publish(task("unowned", TaskState.WORKING, 1, null));

        assertThatThrownBy(() -> engine.publishAs(bob, task("owned", TaskState.COMPLETED, 2, null), bobsSession))
                .as("another caller cannot take an owned task over")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> engine.publishAs(anonymous, task("owned", TaskState.COMPLETED, 2, null), bobsSession))
                .as("an anonymous caller is not the owner either")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> engine.publishAs(alice, task("unowned", TaskState.COMPLETED, 2, null), alicesSession))
                .as("a task nobody owns cannot be claimed afterwards")
                .isInstanceOf(IllegalStateException.class);

        assertThat(engine.get("owned").revision())
                .as("a refused claim changes nothing")
                .isEqualTo(1);
        assertThat(engine.get("unowned").revision()).isEqualTo(1);
        assertThat(pushed)
                .as("a refused claimant's route never receives push traffic")
                .containsExactly("owned@1 -> " + alicesSession, "unowned@1 -> TaskRoute.NONE");
        assertThat(engine.publishAs(alice, task("owned", TaskState.WORKING, 2, null), alicesSession)
                        .revision())
                .as("the owner publishes again")
                .isEqualTo(2);
        assertThat(engine.publish(task("owned", TaskState.COMPLETED, 3, null)).revision())
                .as("the application publishes without a caller: an update, never a claim")
                .isEqualTo(3);
        assertThat(engine.publishAs(anonymous, task("unowned", TaskState.COMPLETED, 2, null), bobsSession)
                        .revision())
                .as("an anonymous caller keeps publishing for a task nobody owns")
                .isEqualTo(2);
    }

    @Test
    void anEvictedOwnedTaskStaysHiddenForItsTtl() throws Exception {
        engine.publishAs(alice, task("with-ttl", TaskState.COMPLETED, 1, TTL), TaskRoute.NONE);
        engine.publishAs(alice, task("without-ttl", TaskState.COMPLETED, 1, null), TaskRoute.NONE);
        var connector = engine.connector();

        clock.advance(KEEP_ALIVE.plusSeconds(1));
        engine.runJanitorSweep();

        assertThat(engine.get("with-ttl")).as("the snapshot was evicted").isNull();
        assertThat(engine.get("without-ttl")).isNull();
        assertThatThrownBy(() -> connector.get().apply(bob, get("with-ttl")))
                .as("the owner is remembered for the ttl, whether or not the snapshot is cached")
                .isInstanceOf(TaskNotFoundException.class);
        assertThat(connector.get().apply(alice, get("with-ttl")).taskId()).isEqualTo("with-ttl");
        assertThat(connector.get().apply(bob, get("without-ttl")).taskId())
                .as("a task without a ttl is bound only while its snapshot is cached")
                .isEqualTo("without-ttl");

        clock.advance(TTL);
        engine.runJanitorSweep();

        assertThat(connector.get().apply(bob, get("with-ttl")).taskId())
                .as("after its ttl an owned task is like any unknown id")
                .isEqualTo("with-ttl");
    }

    @Test
    void anEvictedOwnedTaskStillRefusesAClaimByAnotherCaller() {
        engine.publishAs(alice, task("with-ttl", TaskState.COMPLETED, 1, TTL), TaskRoute.NONE);
        clock.advance(KEEP_ALIVE.plusSeconds(1));
        engine.runJanitorSweep();

        assertThatThrownBy(() -> engine.publishAs(bob, task("with-ttl", TaskState.COMPLETED, 2, TTL), TaskRoute.NONE))
                .isInstanceOf(IllegalStateException.class);
        assertThat(engine.get("with-ttl"))
                .as("nothing was cached for the refused claimant")
                .isNull();
        assertThat(engine.publishAs(alice, task("with-ttl", TaskState.COMPLETED, 2, TTL), TaskRoute.NONE)
                        .revision())
                .isEqualTo(2);
    }

    @Test
    void admitsTheOwnerAndEveryoneForATaskNobodyOwns() {
        engine.publishAs(alice, task("with-ttl", TaskState.COMPLETED, 1, TTL), TaskRoute.NONE);
        engine.publish(task("unowned", TaskState.WORKING, 1, null));
        var aliceKey = Owner.of(alice.securityContext());
        var bobKey = Owner.of(bob.securityContext());

        assertThat(engine.admits(aliceKey, "with-ttl")).isTrue();
        assertThat(engine.admits(bobKey, "with-ttl")).isFalse();
        assertThat(engine.admits(Owner.anonymous(), "with-ttl")).isFalse();
        assertThat(engine.admits(bobKey, "unowned")).isTrue();
        assertThat(engine.admits(bobKey, "unknown")).isTrue();

        clock.advance(KEEP_ALIVE.plusSeconds(1));
        engine.runJanitorSweep();

        assertThat(engine.get("with-ttl")).as("the snapshot was evicted").isNull();
        assertThat(engine.admits(bobKey, "with-ttl"))
                .as("the owner is remembered for the ttl")
                .isFalse();
    }

    @Test
    void noReadCachesATaskTheEngineDoesNotKnowSoItsCreatorCanStillOwnIt() throws Exception {
        var never = new CompletableFuture<Void>();
        var polls = new AtomicInteger();
        var polling = new TaskEngine(
                new TaskEngineSettings(
                        TaskConnector.builder()
                                .get((ctx, request) -> task(
                                        request.taskId(),
                                        polls.incrementAndGet() == 1 ? TaskState.WORKING : TaskState.CANCELLED,
                                        polls.get(),
                                        null))
                                .cancel((ctx, request) -> {})
                                .update((ctx, request) -> {})
                                .build(),
                        Pagination.DEFAULT_PAGE_SIZE,
                        KEEP_ALIVE,
                        null,
                        Duration.ofMillis(1)),
                clock);

        engine.awaitResult(bob, awaitResult("hooked"), never);
        polling.cancelAndAwait(bob, cancel("polled"), never);

        assertThat(engine.get("hooked"))
                .as("awaitResult through the connector's hook")
                .isNull();
        assertThat(polling.get("polled")).as("cancelAndAwait and its poll loop").isNull();
        assertThat(engine.publishAs(alice, task("hooked", TaskState.WORKING, 1, null), TaskRoute.NONE)
                        .taskId())
                .as("the creator is not refused for the earlier read")
                .isEqualTo("hooked");
        assertThatThrownBy(() -> engine.connector().get().apply(bob, get("hooked")))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void removingATaskForgetsItsOwner() throws Exception {
        engine.publishAs(alice, task("owned", TaskState.WORKING, 1, TTL), TaskRoute.NONE);

        assertThat(engine.remove("owned")).isTrue();

        assertThat(engine.connector().get().apply(bob, get("owned")).taskId()).isEqualTo("owned");
    }

    private TaskConnector recordingConnector() {
        return TaskConnector.builder()
                .get((ctx, request) -> {
                    connectorCalls.add("get:" + request.taskId());
                    return task(request.taskId(), TaskState.WORKING, 1, null);
                })
                .cancel((ctx, request) -> connectorCalls.add("cancel:" + request.taskId()))
                .update((ctx, request) -> connectorCalls.add("update:" + request.taskId()))
                .list((ctx, request) -> {
                    connectorCalls.add("list");
                    var all = new ArrayList<TaskSnapshot>();
                    for (var id : List.of("alices", "bobs", "unowned")) {
                        all.add(task(id, TaskState.WORKING, 1, null));
                    }
                    return PaginatedResult.of(all, null, true);
                })
                .awaitResult((ctx, request) -> {
                    connectorCalls.add("awaitResult:" + request.taskId());
                    return task(request.taskId(), TaskState.COMPLETED, 2, null);
                })
                .build();
    }

    private static List<String> ids(PaginatedResult<TaskSnapshot> page) {
        return page.items().stream().map(TaskSnapshot::taskId).toList();
    }

    private static TaskGetRequest get(String taskId) {
        return TaskGetRequest.builder().taskId(taskId).build();
    }

    private static dev.tachyonmcp.api.server.features.tasks.TaskCancelRequest cancel(String taskId) {
        return dev.tachyonmcp.api.server.features.tasks.TaskCancelRequest.builder()
                .taskId(taskId)
                .build();
    }

    private static TaskUpdateRequest update(String taskId) {
        return TaskUpdateRequest.builder().taskId(taskId).build();
    }

    private static dev.tachyonmcp.api.server.features.tasks.TaskAwaitResultRequest awaitResult(String taskId) {
        return dev.tachyonmcp.api.server.features.tasks.TaskAwaitResultRequest.builder()
                .taskId(taskId)
                .build();
    }

    private TaskSnapshot task(String taskId, TaskState status, long revision, @Nullable Duration ttl) {
        return TaskSnapshot.builder()
                .taskId(taskId)
                .status(status)
                .createdAt(clock.instant())
                .lastUpdatedAt(clock.instant())
                .result(status == TaskState.COMPLETED ? TaskResult.completed(Map.of()) : null)
                .ttl(ttl)
                .revision(revision)
                .build();
    }

    private static InteractionContext caller(@Nullable String name) {
        var security =
                name == null ? SecurityContext.anonymous() : SecurityContext.authenticated(() -> name, List.of());
        return new NoopInteractionContext() {
            @Override
            public SecurityContext securityContext() {
                return security;
            }
        };
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
