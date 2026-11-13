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
import dev.tachyonmcp.core.server.security.Owner;
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
 * restart). The store names the owner: the owner this engine knows may refuse a caller, never admit
 * one. A task its store names no owner for reaches nobody unless the server shares it.
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
                .as("a task an anonymous caller creates is public")
                .isEqualTo(TaskSnapshot.PUBLIC_OWNER_KEY);
        assertThatThrownBy(() -> engine.publishAs(bob, owned("forged", TaskState.WORKING, 1, "alice"), TaskRoute.NONE))
                .as("a caller cannot publish a task stamped with someone else's owner")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> engine.publishAs(
                        bob, owned("forged", TaskState.WORKING, 1, TaskSnapshot.PUBLIC_OWNER_KEY), TaskRoute.NONE))
                .as("a signed-in caller's tool cannot make its task public")
                .isInstanceOf(IllegalStateException.class);
        assertThat(engine.get("forged")).isNull();
        for (var blank : List.of("", " ")) {
            assertThatThrownBy(() -> owned("blank", TaskState.WORKING, 1, blank))
                    .as("an owner a store lost and gave back empty is not the public key")
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void aPublicTaskReachesEveryCallerAndStaysPublic() throws Exception {
        var created = engine.publishAs(anonymous, task("public", TaskState.COMPLETED, 1, TTL), TaskRoute.NONE);
        var byTheApplication =
                engine.publish(owned("by-the-application", TaskState.WORKING, 1, TaskSnapshot.PUBLIC_OWNER_KEY));
        store(created);
        store(byTheApplication);
        var connector = engine.connector();

        assertThat(byTheApplication.ownerKey())
                .as("the application may publish a public task")
                .isEqualTo(TaskSnapshot.PUBLIC_OWNER_KEY);
        for (var caller : List.of(alice, bob, anonymous)) {
            for (var taskId : List.of("public", "by-the-application")) {
                assertThat(connector.get().apply(caller, get(taskId)).taskId()).isEqualTo(taskId);
                connector.cancel().apply(caller, cancel(taskId));
                assertThat(connector
                                .awaitResult()
                                .apply(caller, awaitResult(taskId))
                                .taskId())
                        .isEqualTo(taskId);
            }
            assertThat(engine.admits(Owner.of(caller.securityContext()), created))
                    .as("a stream any caller opened follows a public task")
                    .isTrue();
        }
        assertThatThrownBy(() -> engine.publishAs(alice, task("public", TaskState.COMPLETED, 2, TTL), TaskRoute.NONE))
                .as("a public task is nobody's to take over")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> engine.publish(owned("public", TaskState.COMPLETED, 2, "alice")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(engine.publishAs(anonymous, task("public", TaskState.COMPLETED, 2, TTL), TaskRoute.NONE)
                        .revision())
                .as("an anonymous caller publishes for it again")
                .isEqualTo(2);

        clock.advance(KEEP_ALIVE.plusSeconds(1));
        engine.runJanitorSweep();

        assertThat(engine.get("public")).as("the snapshot was evicted").isNull();
        assertThat(connector.get().apply(bob, get("public")).taskId())
                .as("an evicted task stays public: its store names the public key")
                .isEqualTo("public");
        assertThatThrownBy(() -> engine.publishAs(alice, task("public", TaskState.COMPLETED, 3, TTL), TaskRoute.NONE))
                .isInstanceOf(IllegalStateException.class);

        clock.advance(TTL);
        engine.runJanitorSweep();

        for (var caller : List.of(alice, bob, anonymous)) {
            for (var each : List.of(engine, engine(false))) {
                assertThat(each.connector().get().apply(caller, get("public")).ownerKey())
                        .as("the stored public key keeps the task public past its ttl and after a restart")
                        .isEqualTo(TaskSnapshot.PUBLIC_OWNER_KEY);
            }
        }
    }

    @Test
    void anotherCallerNeverReachesTheRealConnectorForACachedOwnedTask() throws Exception {
        store(engine.publishAs(alice, task("owned", TaskState.WORKING, 1, null), new TestRoute("alice-session")));
        var connector = engine.connector();
        connectorCalls.clear();

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
        connectorCalls.clear();
        connector.cancel().apply(alice, cancel("owned"));
        assertThat(connectorCalls)
                .as("the store names the owner before a cancel, even for a cached task")
                .containsSubsequence("get:owned", "cancel:owned");
    }

    @Test
    void aRestartedEngineReadsTheOwnerFromTheConnector() throws Exception {
        store(engine.publishAs(alice, task("owned", TaskState.WORKING, 1, null), TaskRoute.NONE));
        var restartedEngine = engine(false);
        var restarted = restartedEngine.connector();

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
        assertThatThrownBy(
                        () -> restartedEngine.publishAs(bob, task("owned", TaskState.WORKING, 2, null), TaskRoute.NONE))
                .as("a tool cannot claim a task its store holds for someone else")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() ->
                        restartedEngine.publishAs(anonymous, task("owned", TaskState.WORKING, 2, null), TaskRoute.NONE))
                .as("nor can an anonymous caller's tool make it public")
                .isInstanceOf(IllegalStateException.class);
        assertThat(restartedEngine.get("owned"))
                .as("a refused claim caches nothing")
                .isNull();

        assertThat(restarted.get().apply(alice, get("owned")).ownerKey()).isEqualTo("alice");
        connectorCalls.clear();
        restarted.cancel().apply(alice, cancel("owned"));
        assertThat(connectorCalls).containsSubsequence("get:owned", "cancel:owned");
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
    void aStoreThatNamesNoOwnerHidesTheTaskUnlessTheServerSharesUnownedTasks() throws Exception {
        store(task("kept", TaskState.COMPLETED, 1, TTL));
        var shared = engine(true);

        for (var caller : List.of(alice, bob, anonymous)) {
            assertThatThrownBy(
                            () -> engine.publishAs(caller, task("kept", TaskState.COMPLETED, 1, TTL), TaskRoute.NONE))
                    .as("where unowned tasks are hidden, a task its store names no owner for cannot be created")
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> engine.connector().get().apply(caller, get("kept")))
                    .as("nobody reaches a task nobody owns unless the server shares it")
                    .isInstanceOf(TaskNotFoundException.class);
        }
        assertThat(engine.get("kept")).as("a refused claim caches nothing").isNull();
        assertThat(shared.publishAs(alice, task("kept", TaskState.COMPLETED, 1, TTL), TaskRoute.NONE)
                        .ownerKey())
                .as("creation still works where unowned tasks are shared")
                .isEqualTo("alice");
    }

    @Test
    void theOwnerThisEngineKnowsRefusesAnotherCallerWhileCachedAndAfterEviction() throws Exception {
        store(task("kept", TaskState.COMPLETED, 1, TTL));
        var shared = engine(true);
        shared.publishAs(alice, task("kept", TaskState.COMPLETED, 1, TTL), TaskRoute.NONE);

        assertOnlyAliceReachesKept(shared, "cached");

        clock.advance(KEEP_ALIVE.plusSeconds(1));
        shared.runJanitorSweep();

        assertThat(shared.get("kept")).as("the snapshot was evicted").isNull();
        assertOnlyAliceReachesKept(shared, "evicted");
        assertThat(shared.get("kept")).as("a refused claim caches nothing").isNull();

        clock.advance(TTL);
        shared.runJanitorSweep();

        assertThat(shared.connector().get().apply(bob, get("kept")).taskId())
                .as("past its ttl only the store knows the task, and it names no owner")
                .isEqualTo("kept");
        assertThat(shared.publishAs(bob, task("kept", TaskState.WORKING, 1, TTL), TaskRoute.NONE)
                        .ownerKey())
                .as("so where unowned tasks are shared the id is free again")
                .isEqualTo("bob");
    }

    /** The store names no owner for the task "kept"; {@code shared} knows alice created it. */
    private void assertOnlyAliceReachesKept(TaskEngine shared, String stage) throws Exception {
        var connector = shared.connector();
        var bobsSession = new TestRoute("bob-session");
        connectorCalls.clear();

        assertThatThrownBy(() -> connector.get().apply(bob, get("kept")))
                .as("%s: sharing unowned tasks never shares one this engine knows the owner of", stage)
                .isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> connector.cancel().apply(bob, cancel("kept")))
                .isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> connector.get().apply(anonymous, get("kept")))
                .isInstanceOf(TaskNotFoundException.class);
        assertThat(connectorCalls)
                .as("%s: the owner this engine knows refuses before the connector runs", stage)
                .isEmpty();
        assertThatThrownBy(() -> shared.publishAs(bob, task("kept", TaskState.COMPLETED, 2, TTL), bobsSession))
                .as("%s: another caller cannot claim the id", stage)
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> shared.publishAs(anonymous, task("kept", TaskState.COMPLETED, 2, TTL), bobsSession))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> shared.publish(owned("kept", TaskState.COMPLETED, 2, "bob")))
                .isInstanceOf(IllegalStateException.class);

        connectorCalls.clear();
        connector.cancel().apply(alice, cancel("kept"));
        assertThat(connector.get().apply(alice, get("kept")).taskId())
                .as("%s: the owner still reaches a task whose store names no owner", stage)
                .isEqualTo("kept");
        assertThat(connectorCalls).containsSubsequence("get:kept", "cancel:kept");
    }

    @Test
    void theRetainedOwnerComesBackWithTheTaskAndGoesWhenTheApplicationRemovesIt() throws Exception {
        var sharing = engine(true);
        store(task("removed", TaskState.COMPLETED, 1, TTL));
        store(task("without-ttl", TaskState.COMPLETED, 1, null));
        sharing.publishAs(alice, task("kept", TaskState.COMPLETED, 1, TTL), TaskRoute.NONE);
        sharing.publishAs(alice, task("removed", TaskState.COMPLETED, 1, TTL), TaskRoute.NONE);
        sharing.publishAs(alice, task("without-ttl", TaskState.COMPLETED, 1, null), TaskRoute.NONE);
        clock.advance(KEEP_ALIVE.plusSeconds(1));
        sharing.runJanitorSweep();

        assertThat(sharing.publish(task("kept", TaskState.COMPLETED, 2, TTL)).ownerKey())
                .as("an application publish without an owner takes the retained one")
                .isEqualTo("alice");
        assertThat(sharing.get("kept").ownerKey()).isEqualTo("alice");
        assertThat(sharing.remove("removed")).as("no cached copy is left").isFalse();
        assertThat(sharing.connector().get().apply(anonymous, get("removed")).ownerKey())
                .as("removing a task forgets its retained owner")
                .isNull();
        assertThat(sharing.publishAs(bob, task("removed", TaskState.WORKING, 1, TTL), TaskRoute.NONE)
                        .ownerKey())
                .isEqualTo("bob");
        assertThat(sharing.connector()
                        .get()
                        .apply(anonymous, get("without-ttl"))
                        .taskId())
                .as("a task without a ttl is not retained: its store must name the owner")
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
    void aListingHoldsTheCallersTasksAndThePublicOnes() throws Exception {
        store(engine.publishAs(alice, task("alices", TaskState.WORKING, 1, null), TaskRoute.NONE));
        store(engine.publishAs(bob, task("bobs", TaskState.WORKING, 1, null), TaskRoute.NONE));
        store(engine.publishAs(anonymous, task("public", TaskState.WORKING, 1, null), TaskRoute.NONE));
        store(task("unowned", TaskState.WORKING, 1, null));
        var list = TaskListRequest.builder().limit(10).build();

        assertThat(ids(engine.connector().list().apply(alice, list))).containsExactly("alices", "public");
        assertThat(ids(engine.connector().list().apply(bob, list))).containsExactly("bobs", "public");
        assertThat(ids(engine.connector().list().apply(anonymous, list)))
                .as("an anonymous caller reaches public tasks, not the ones nobody owns")
                .containsExactly("public");
        assertThat(ids(engine(false).connector().list().apply(alice, list)))
                .as("a restarted engine filters by the stored owner")
                .containsExactly("alices", "public");
    }

    @Test
    void aTaskNobodyOwnsIsHiddenFromEveryCallerUnlessTheServerSharesIt() throws Exception {
        store(task("unowned", TaskState.WORKING, 1, null));
        store(engine.publishAs(alice, task("alices", TaskState.WORKING, 1, null), TaskRoute.NONE));
        var shared = engine(true).connector();
        var list = TaskListRequest.builder().limit(10).build();

        assertThatThrownBy(() -> engine.connector().get().apply(bob, get("unowned")))
                .isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> engine.connector().cancel().apply(bob, cancel("unowned")))
                .isInstanceOf(TaskNotFoundException.class);
        assertThat(connectorCalls).as("the refused cancel never ran").doesNotContain("cancel:unowned");
        assertThatThrownBy(() -> engine.connector().get().apply(anonymous, get("unowned")))
                .as("a caller let through as anonymous is no exception")
                .isInstanceOf(TaskNotFoundException.class);

        assertThat(shared.get().apply(bob, get("unowned")).taskId()).isEqualTo("unowned");
        assertThat(shared.get().apply(anonymous, get("unowned")).taskId())
                .as("a server without authentication shares what nobody owns")
                .isEqualTo("unowned");
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
        assertThatThrownBy(
                        () -> engine.publishAs(anonymous, task("unowned", TaskState.COMPLETED, 2, null), bobsSession))
                .as("an anonymous caller cannot open a task nobody reaches to everyone")
                .isInstanceOf(IllegalStateException.class);
        assertThat(engine.get("unowned").ownerKey()).isNull();

        var sharing = engine(true);
        sharing.publish(task("unowned", TaskState.WORKING, 1, null));
        assertThatThrownBy(() -> sharing.publishAs(alice, task("unowned", TaskState.COMPLETED, 2, null), alicesSession))
                .as("sharing a task nobody owns does not let a caller claim it")
                .isInstanceOf(IllegalStateException.class);
        var opened = sharing.publishAs(anonymous, task("unowned", TaskState.COMPLETED, 2, null), bobsSession);
        assertThat(opened.revision())
                .as("where everyone reaches it anyway, an anonymous caller publishes: worker first, tool second")
                .isEqualTo(2);
        assertThat(opened.ownerKey()).as("which makes the task public").isEqualTo(TaskSnapshot.PUBLIC_OWNER_KEY);
        assertThatThrownBy(() -> sharing.publish(owned("unowned", TaskState.COMPLETED, 3, "alice")))
                .as("and public it stays")
                .isInstanceOf(IllegalStateException.class);
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
                        true),
                clock);
        var hooking = engine(true);
        store(task("hooked", TaskState.COMPLETED, 1, null));

        hooking.awaitResult(anonymous, awaitResult("hooked"), never);
        polling.cancelAndAwait(anonymous, cancel("polled"), never);

        assertThat(hooking.get("hooked"))
                .as("awaitResult through the connector's hook")
                .isNull();
        assertThat(polling.get("polled")).as("cancelAndAwait and its poll loop").isNull();
        store(hooking.publishAs(alice, task("hooked", TaskState.WORKING, 1, null), TaskRoute.NONE));
        assertThat(hooking.get("hooked").ownerKey())
                .as("the creator is not refused for the earlier read")
                .isEqualTo("alice");
        assertThatThrownBy(() -> hooking.connector().get().apply(bob, get("hooked")))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void aTaskOwnedByTheTimeTheAwaitHookReturnsIsHandedToItsOwnerOnly() throws Exception {
        var never = new CompletableFuture<Void>();
        var adopting = new TaskEngine(
                new TaskEngineSettings(
                        TaskConnector.builder()
                                .get((ctx, request) -> task(request.taskId(), TaskState.WORKING, 1, null))
                                .cancel((ctx, request) -> {})
                                .update((ctx, request) -> {})
                                .awaitResult((ctx, request) -> owned(request.taskId(), TaskState.COMPLETED, 2, "alice"))
                                .build(),
                        Pagination.DEFAULT_PAGE_SIZE,
                        KEEP_ALIVE,
                        null,
                        TaskEngineSettings.DEFAULT_RESULT_POLL_INTERVAL,
                        true),
                clock);
        var connector = adopting.connector();

        assertThat(connector.get().apply(bob, get("adopted")).ownerKey())
                .as("nobody owns the task when the wait starts, and the server shares unowned tasks")
                .isNull();
        assertThatThrownBy(() -> connector.awaitResult().apply(bob, awaitResult("adopted")))
                .as("the result the hook returns names alice, so bob does not get it")
                .isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> connector.awaitResult().apply(anonymous, awaitResult("adopted")))
                .isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> adopting.awaitResult(bob, awaitResult("adopted"), never))
                .as("the legacy tasks/result path goes through the same check")
                .isInstanceOf(TaskNotFoundException.class);
        assertThat(adopting.get("adopted")).as("a refused wait caches nothing").isNull();
        var result = connector.awaitResult().apply(alice, awaitResult("adopted"));
        assertThat(result.status()).isEqualTo(TaskState.COMPLETED);
        assertThat(result.ownerKey()).isEqualTo("alice");
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

    /** Hands back what it was given, as a real connector does; it does not know an id it never stored. */
    private TaskConnector storingConnector() {
        return TaskConnector.builder()
                .get((ctx, request) -> {
                    connectorCalls.add("get:" + request.taskId());
                    return read(request.taskId());
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
                    return read(request.taskId());
                })
                .build();
    }

    private TaskSnapshot read(String taskId) throws TaskNotFoundException {
        var known = stored.get(taskId);
        if (known == null) {
            throw new TaskNotFoundException(taskId);
        }
        return known;
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
