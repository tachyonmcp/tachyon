/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.tasks.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.domain.TaskResult;
import dev.tachyonmcp.api.server.features.PaginatedResult;
import dev.tachyonmcp.api.server.features.tasks.TaskAwaitResultRequest;
import dev.tachyonmcp.api.server.features.tasks.TaskCancelRequest;
import dev.tachyonmcp.api.server.features.tasks.TaskConnector;
import dev.tachyonmcp.api.server.features.tasks.TaskGetRequest;
import dev.tachyonmcp.api.server.features.tasks.TaskListRequest;
import dev.tachyonmcp.api.server.features.tasks.TaskNotFoundException;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.api.server.features.tasks.TaskState;
import dev.tachyonmcp.api.server.features.tasks.TaskUpdateRequest;
import dev.tachyonmcp.api.server.security.SecurityContext;
import dev.tachyonmcp.core.server.features.Pagination;
import dev.tachyonmcp.core.server.session.NoopInteractionContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * A task belongs to the caller that created it, and its owner travels with its snapshot, so the
 * connector that stores the task stores the owner too. The engine answers anyone else as it answers
 * an unknown id, whether the task is cached, evicted, or unknown to this engine (another node, a
 * restart). The engine remembers the owner of an evicted task until the task's ttl, for a connector
 * that stores none. A task nobody owns is reached only by anonymous callers unless the server shares
 * it.
 */
class TaskOwnershipTest {

    private static final Duration KEEP_ALIVE = Duration.ofMinutes(5);
    private static final Duration TTL = Duration.ofHours(1);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-15T07:00:00Z"));
    private final List<String> connectorCalls = new ArrayList<>();
    private final Map<String, TaskSnapshot> stored = new ConcurrentHashMap<>();
    private final List<String> pushed = new ArrayList<>();
    private final InteractionContext alice = caller("alice");
    private final InteractionContext bob = caller("bob");
    private final InteractionContext anonymous = caller(null);
    private final TaskEngine engine = engine(false);

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
    void aCallersPublishStampsItsOwnerOnTheSnapshot() {
        var owned = engine.publishAs(alice, task("owned", TaskState.WORKING, 1, null), TaskRoute.NONE);
        var anonymousTask = engine.publishAs(anonymous, task("anonymous", TaskState.WORKING, 1, null), TaskRoute.NONE);

        assertThat(owned.ownerKey()).isEqualTo("alice");
        assertThat(engine.get("owned")).isEqualTo(owned);
        assertThat(anonymousTask.ownerKey())
                .as("an anonymous caller owns nothing")
                .isNull();
        assertThatThrownBy(() -> engine.publishAs(bob, owned("forged", TaskState.WORKING, 1, "alice"), TaskRoute.NONE))
                .as("a caller cannot publish a task stamped with someone else's owner")
                .isInstanceOf(IllegalStateException.class);
        assertThat(engine.get("forged")).isNull();
    }

    @Test
    void anotherCallerNeverReachesTheRealConnectorForACachedOwnedTask() throws Exception {
        store(engine.publishAs(alice, task("owned", TaskState.WORKING, 1, null), new TestRoute("alice-session")));
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
        connector.cancel().apply(alice, cancel("owned"));
        assertThat(connectorCalls)
                .as("the cached owner needs no extra read before a cancel")
                .containsExactly("get:owned", "cancel:owned");
    }

    @Test
    void aRestartedEngineReadsTheOwnerFromTheConnector() throws Exception {
        store(engine.publishAs(alice, task("owned", TaskState.WORKING, 1, null), TaskRoute.NONE));
        var restarted = engine(false).connector();

        assertThatThrownBy(() -> restarted.get().apply(bob, get("owned"))).isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> restarted.cancel().apply(bob, cancel("owned")))
                .isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> restarted.update().apply(bob, update("owned")))
                .isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> restarted.awaitResult().apply(bob, awaitResult("owned")))
                .isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> restarted.get().apply(anonymous, get("owned")))
                .isInstanceOf(TaskNotFoundException.class);
        assertThat(connectorCalls)
                .as("an uncached task is read to learn its owner, and nothing else reaches the connector")
                .containsOnly("get:owned");

        connectorCalls.clear();
        assertThat(restarted.get().apply(alice, get("owned")).ownerKey()).isEqualTo("alice");
        restarted.cancel().apply(alice, cancel("owned"));
        assertThat(connectorCalls).containsExactly("get:owned", "get:owned", "cancel:owned");
    }

    @Test
    void anEvictedTaskStaysHiddenWhetherOrNotItHasATtl() throws Exception {
        store(engine.publishAs(alice, task("with-ttl", TaskState.COMPLETED, 1, TTL), TaskRoute.NONE));
        store(engine.publishAs(alice, task("without-ttl", TaskState.COMPLETED, 1, null), TaskRoute.NONE));
        var connector = engine.connector();

        clock.advance(KEEP_ALIVE.plusSeconds(1));
        engine.runJanitorSweep();

        assertThat(engine.get("with-ttl")).as("the snapshot was evicted").isNull();
        assertThat(engine.get("without-ttl")).isNull();
        for (var taskId : List.of("with-ttl", "without-ttl")) {
            assertThatThrownBy(() -> connector.get().apply(bob, get(taskId)))
                    .as("%s: the owner lives with the stored task, not with the cache", taskId)
                    .isInstanceOf(TaskNotFoundException.class);
            assertThat(connector.get().apply(alice, get(taskId)).taskId()).isEqualTo(taskId);
        }

        clock.advance(TTL);
        engine.runJanitorSweep();

        assertThatThrownBy(() -> connector.get().apply(bob, get("with-ttl")))
                .as("past its ttl the task is the connector's to expire; its owner still holds")
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void anEvictedTaskKeepsItsOwnerUntilItsTtlEvenIfTheConnectorStoresNone() throws Exception {
        var shared = engine(true);
        var engines = List.of(engine, shared);
        for (var each : engines) {
            each.publishAs(alice, task("kept", TaskState.COMPLETED, 1, TTL), TaskRoute.NONE);
        }
        clock.advance(KEEP_ALIVE.plusSeconds(1));

        for (var each : engines) {
            each.runJanitorSweep();
            var connector = each.connector();
            var bobsSession = new TestRoute("bob-session");

            assertThat(each.get("kept")).as("the snapshot was evicted").isNull();
            assertThatThrownBy(() -> connector.get().apply(bob, get("kept")))
                    .as("sharing unowned tasks never shares an evicted owned one")
                    .isInstanceOf(TaskNotFoundException.class);
            assertThatThrownBy(() -> connector.cancel().apply(bob, cancel("kept")))
                    .isInstanceOf(TaskNotFoundException.class);
            assertThatThrownBy(() -> connector.get().apply(anonymous, get("kept")))
                    .isInstanceOf(TaskNotFoundException.class);
            assertThat(connectorCalls)
                    .as("the retained owner refuses before the connector runs")
                    .isEmpty();
            assertThatThrownBy(() -> each.publishAs(bob, task("kept", TaskState.COMPLETED, 2, TTL), bobsSession))
                    .as("another caller cannot claim the id of an evicted task")
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> each.publishAs(anonymous, task("kept", TaskState.COMPLETED, 2, TTL), bobsSession))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> each.publish(owned("kept", TaskState.COMPLETED, 2, "bob")))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(each.get("kept")).as("a refused claim caches nothing").isNull();

            connector.cancel().apply(alice, cancel("kept"));
            assertThat(connector.get().apply(alice, get("kept")).taskId())
                    .as("the owner still reaches a task whose connector stores no owner")
                    .isEqualTo("kept");
            assertThat(connectorCalls)
                    .as("the retained owner needs no extra read before a cancel")
                    .containsExactly("cancel:kept", "get:kept");
            connectorCalls.clear();
        }

        clock.advance(TTL);
        engines.forEach(TaskEngine::runJanitorSweep);

        assertThat(shared.connector().get().apply(bob, get("kept")).taskId())
                .as("past its ttl only the connector knows the task, and it stores no owner")
                .isEqualTo("kept");
        assertThatThrownBy(() -> engine.connector().get().apply(bob, get("kept")))
                .isInstanceOf(TaskNotFoundException.class);
        assertThat(engine.connector().get().apply(anonymous, get("kept")).taskId())
                .isEqualTo("kept");
        assertThat(engine.publishAs(bob, task("kept", TaskState.WORKING, 1, TTL), TaskRoute.NONE)
                        .ownerKey())
                .as("past its ttl the id is free again")
                .isEqualTo("bob");
    }

    @Test
    void theRetainedOwnerComesBackWithTheTaskAndGoesWhenTheApplicationRemovesIt() throws Exception {
        engine.publishAs(alice, task("kept", TaskState.COMPLETED, 1, TTL), TaskRoute.NONE);
        engine.publishAs(alice, task("removed", TaskState.COMPLETED, 1, TTL), TaskRoute.NONE);
        engine.publishAs(alice, task("without-ttl", TaskState.COMPLETED, 1, null), TaskRoute.NONE);
        clock.advance(KEEP_ALIVE.plusSeconds(1));
        engine.runJanitorSweep();

        assertThat(engine.publish(task("kept", TaskState.COMPLETED, 2, TTL)).ownerKey())
                .as("an application publish without an owner takes the retained one")
                .isEqualTo("alice");
        assertThat(engine.get("kept").ownerKey()).isEqualTo("alice");
        assertThat(engine.remove("removed")).as("no cached copy is left").isFalse();
        assertThat(engine.connector().get().apply(anonymous, get("removed")).ownerKey())
                .as("removing a task forgets its retained owner")
                .isNull();
        assertThat(engine.publishAs(bob, task("removed", TaskState.WORKING, 1, TTL), TaskRoute.NONE)
                        .ownerKey())
                .isEqualTo("bob");
        assertThat(engine.connector().get().apply(anonymous, get("without-ttl")).taskId())
                .as("a task without a ttl is not retained: its connector must store the owner")
                .isEqualTo("without-ttl");
    }

    @Test
    void anUnownedCachedTaskTakesTheOwnerItsConnectorOrApplicationNames() {
        engine.publish(task("read", TaskState.WORKING, 1, null));
        engine.publish(task("published", TaskState.WORKING, 2, null));

        var read = engine.refresh(owned("read", TaskState.COMPLETED, 2, "alice"));
        var published = engine.publish(owned("published", TaskState.WORKING, 2, "alice"));

        assertThat(read.revision())
                .as("the connector's newer snapshot is cached, not dropped")
                .isEqualTo(2);
        assertThat(read.ownerKey()).isEqualTo("alice");
        assertThat(published.ownerKey())
                .as("the owner is taken even at an equal revision")
                .isEqualTo("alice");
        assertThat(engine.get("published").ownerKey()).isEqualTo("alice");
        assertThatThrownBy(() -> engine.publish(owned("published", TaskState.COMPLETED, 3, "bob")))
                .as("once taken, the owner never changes")
                .isInstanceOf(IllegalStateException.class);
        assertThat(engine.refresh(owned("read", TaskState.COMPLETED, 3, "bob")).revision())
                .isEqualTo(2);
        assertThat(engine.get("read").ownerKey()).isEqualTo("alice");
        assertThat(pushed)
                .containsExactly(
                        "read@1 -> TaskRoute.NONE", "published@2 -> TaskRoute.NONE", "read@2 -> TaskRoute.NONE");
    }

    @Test
    void aListingHoldsOnlyTheCallersTasks() throws Exception {
        store(engine.publishAs(alice, task("alices", TaskState.WORKING, 1, null), TaskRoute.NONE));
        store(engine.publishAs(bob, task("bobs", TaskState.WORKING, 1, null), TaskRoute.NONE));
        store(task("unowned", TaskState.WORKING, 1, null));
        var list = TaskListRequest.builder().limit(10).build();

        assertThat(ids(engine.connector().list().apply(alice, list))).containsExactly("alices");
        assertThat(ids(engine.connector().list().apply(bob, list))).containsExactly("bobs");
        assertThat(ids(engine.connector().list().apply(anonymous, list))).containsExactly("unowned");
        assertThat(ids(engine(false).connector().list().apply(alice, list)))
                .as("a restarted engine filters by the stored owner")
                .containsExactly("alices");
    }

    @Test
    void aTaskNobodyOwnsIsHiddenFromNamedCallersUnlessTheServerSharesIt() throws Exception {
        store(task("unowned", TaskState.WORKING, 1, null));
        store(engine.publishAs(alice, task("alices", TaskState.WORKING, 1, null), TaskRoute.NONE));
        var shared = engine(true).connector();
        var list = TaskListRequest.builder().limit(10).build();

        assertThatThrownBy(() -> engine.connector().get().apply(bob, get("unowned")))
                .isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> engine.connector().cancel().apply(bob, cancel("unowned")))
                .isInstanceOf(TaskNotFoundException.class);
        assertThat(connectorCalls).as("the refused cancel never ran").doesNotContain("cancel:unowned");
        assertThat(engine.connector().get().apply(anonymous, get("unowned")).taskId())
                .as("an anonymous caller still reaches what nobody owns: a server without authentication")
                .isEqualTo("unowned");

        assertThat(shared.get().apply(bob, get("unowned")).taskId()).isEqualTo("unowned");
        assertThat(ids(shared.list().apply(bob, list))).containsExactly("unowned");
        assertThatThrownBy(() -> shared.get().apply(bob, get("alices")))
                .as("sharing unowned tasks never shares an owned one")
                .isInstanceOf(TaskNotFoundException.class);
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
        assertThatThrownBy(() -> engine.publish(owned("owned", TaskState.COMPLETED, 2, "bob")))
                .as("the application cannot hand an owned task to someone else")
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
        var update = engine.publish(task("owned", TaskState.COMPLETED, 3, null));
        assertThat(update.revision())
                .as("the application publishes without a caller: an update, never a claim")
                .isEqualTo(3);
        assertThat(update.ownerKey())
                .as("an update without an owner keeps the owner")
                .isEqualTo("alice");
        assertThat(engine.publish(owned("by-the-application", TaskState.WORKING, 1, "bob"))
                        .ownerKey())
                .as("the application may create a task for a caller")
                .isEqualTo("bob");
        assertThat(engine.publishAs(anonymous, task("unowned", TaskState.COMPLETED, 2, null), bobsSession)
                        .revision())
                .as("an anonymous caller keeps publishing for a task nobody owns")
                .isEqualTo(2);
    }

    @Test
    void aConnectorReadNeverChangesTheCachedOwner() {
        engine.publishAs(alice, task("owned", TaskState.WORKING, 1, null), TaskRoute.NONE);

        var unstamped = engine.refresh(task("owned", TaskState.WORKING, 2, null));
        var foreign = engine.refresh(owned("owned", TaskState.WORKING, 3, "bob"));

        assertThat(unstamped.revision()).isEqualTo(2);
        assertThat(unstamped.ownerKey())
                .as("a read without an owner keeps the cached one")
                .isEqualTo("alice");
        assertThat(foreign.revision())
                .as("a read naming another owner is not cached")
                .isEqualTo(2);
        assertThat(engine.get("owned").ownerKey()).isEqualTo("alice");
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
                        Duration.ofMillis(1),
                        false),
                clock);

        engine.awaitResult(anonymous, awaitResult("hooked"), never);
        polling.cancelAndAwait(anonymous, cancel("polled"), never);

        assertThat(engine.get("hooked"))
                .as("awaitResult through the connector's hook")
                .isNull();
        assertThat(polling.get("polled")).as("cancelAndAwait and its poll loop").isNull();
        store(engine.publishAs(alice, task("hooked", TaskState.WORKING, 1, null), TaskRoute.NONE));
        assertThat(engine.get("hooked").ownerKey())
                .as("the creator is not refused for the earlier read")
                .isEqualTo("alice");
        assertThatThrownBy(() -> engine.connector().get().apply(bob, get("hooked")))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void removingATaskDropsOnlyTheCachedCopy() throws Exception {
        store(engine.publishAs(alice, task("owned", TaskState.WORKING, 1, TTL), TaskRoute.NONE));

        assertThat(engine.remove("owned")).isTrue();

        assertThat(engine.get("owned")).isNull();
        assertThatThrownBy(() -> engine.connector().get().apply(bob, get("owned")))
                .as("the stored task keeps its owner")
                .isInstanceOf(TaskNotFoundException.class);
    }

    private TaskEngine engine(boolean shareUnownedTasks) {
        return new TaskEngine(
                new TaskEngineSettings(
                        storingConnector(),
                        Pagination.DEFAULT_PAGE_SIZE,
                        KEEP_ALIVE,
                        null,
                        TaskEngineSettings.DEFAULT_RESULT_POLL_INTERVAL,
                        shareUnownedTasks),
                clock);
    }

    private void store(TaskSnapshot snapshot) {
        stored.put(snapshot.taskId(), snapshot);
    }

    /** Hands back what it was given, as a real connector does; an id it never stored reads as unowned. */
    private TaskConnector storingConnector() {
        return TaskConnector.builder()
                .get((ctx, request) -> {
                    connectorCalls.add("get:" + request.taskId());
                    return read(request.taskId(), TaskState.WORKING);
                })
                .cancel((ctx, request) -> connectorCalls.add("cancel:" + request.taskId()))
                .update((ctx, request) -> connectorCalls.add("update:" + request.taskId()))
                .list((ctx, request) -> {
                    connectorCalls.add("list");
                    var all = stored.values().stream()
                            .sorted(Comparator.comparing(TaskSnapshot::taskId))
                            .toList();
                    return PaginatedResult.of(all, null, true);
                })
                .awaitResult((ctx, request) -> {
                    connectorCalls.add("awaitResult:" + request.taskId());
                    return read(request.taskId(), TaskState.COMPLETED);
                })
                .build();
    }

    private TaskSnapshot read(String taskId, TaskState fallbackStatus) {
        var known = stored.get(taskId);
        return known != null ? known : task(taskId, fallbackStatus, 1, null);
    }

    private static List<String> ids(PaginatedResult<TaskSnapshot> page) {
        return page.items().stream().map(TaskSnapshot::taskId).toList();
    }

    private static TaskGetRequest get(String taskId) {
        return TaskGetRequest.builder().taskId(taskId).build();
    }

    private static TaskCancelRequest cancel(String taskId) {
        return TaskCancelRequest.builder().taskId(taskId).build();
    }

    private static TaskUpdateRequest update(String taskId) {
        return TaskUpdateRequest.builder().taskId(taskId).build();
    }

    private static TaskAwaitResultRequest awaitResult(String taskId) {
        return TaskAwaitResultRequest.builder().taskId(taskId).build();
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

    private TaskSnapshot owned(String taskId, TaskState status, long revision, String ownerKey) {
        return TaskSnapshot.builder()
                .from(task(taskId, status, revision, null))
                .ownerKey(ownerKey)
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
