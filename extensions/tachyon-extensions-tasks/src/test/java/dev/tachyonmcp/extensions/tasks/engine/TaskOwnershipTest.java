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
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * A task belongs to the caller that created it, and its owner travels with its snapshot, so the
 * connector that stores the task stores the owner too. The engine answers anyone else as it answers
 * an unknown id, whether the task is cached, evicted, or unknown to this engine (another node, a
 * restart). The store names the owner: the owner this engine knows may refuse a caller, never admit
 * one. A task its store names no owner for reaches nobody unless the server has no authentication.
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
        var owned = start(engine, alice, task("owned", TaskState.WORKING, 1, null), TaskRoute.NONE);
        var anonymousTask = start(engine, anonymous, task("anonymous", TaskState.WORKING, 1, null), TaskRoute.NONE);

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
        var created = start(engine, anonymous, task("public", TaskState.COMPLETED, 1, TTL), TaskRoute.NONE);
        var byTheApplication =
                engine.publish(owned("by-the-application", TaskState.WORKING, 1, TaskSnapshot.PUBLIC_OWNER_KEY));
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
        start(engine, alice, task("owned", TaskState.WORKING, 1, null), new TestRoute("alice-session"));
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
        start(engine, alice, task("owned", TaskState.WORKING, 1, null), TaskRoute.NONE);
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
        start(engine, alice, task("with-ttl", TaskState.COMPLETED, 1, TTL), TaskRoute.NONE);
        start(engine, alice, task("without-ttl", TaskState.COMPLETED, 1, null), TaskRoute.NONE);
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
    void aStoreThatNamesNoOwnerHidesTheTaskUnlessTheServerHasNoAuthentication() throws Exception {
        store(task("kept", TaskState.COMPLETED, 1, TTL));
        var noAuth = engine(true);

        for (var caller : List.of(alice, bob, anonymous)) {
            assertThatThrownBy(
                            () -> engine.publishAs(caller, task("kept", TaskState.COMPLETED, 1, TTL), TaskRoute.NONE))
                    .as("where unowned tasks are hidden, a task its store names no owner for cannot be created")
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> engine.connector().get().apply(caller, get("kept")))
                    .as("with authentication nobody reaches a task nobody owns")
                    .isInstanceOf(TaskNotFoundException.class);
        }
        assertThat(engine.get("kept")).as("a refused claim caches nothing").isNull();
        assertThat(noAuth.publishAs(alice, task("kept", TaskState.COMPLETED, 1, TTL), TaskRoute.NONE)
                        .ownerKey())
                .as("creation still works on a server without authentication")
                .isEqualTo("alice");
    }

    @Test
    void theOwnerThisEngineKnowsRefusesAnotherCallerWhileCachedAndAfterEviction() throws Exception {
        store(task("kept", TaskState.COMPLETED, 1, TTL));
        var noAuth = engine(true);
        noAuth.publishAs(alice, task("kept", TaskState.COMPLETED, 1, TTL), TaskRoute.NONE);

        assertOnlyAliceReachesKept(noAuth, "cached");

        clock.advance(KEEP_ALIVE.plusSeconds(1));
        noAuth.runJanitorSweep();

        assertThat(noAuth.get("kept")).as("the snapshot was evicted").isNull();
        assertOnlyAliceReachesKept(noAuth, "evicted");
        assertThat(noAuth.get("kept")).as("a refused claim caches nothing").isNull();

        clock.advance(TTL);
        noAuth.runJanitorSweep();

        assertThat(noAuth.connector().get().apply(bob, get("kept")).taskId())
                .as("past its ttl only the store knows the task, and it names no owner")
                .isEqualTo("kept");
        assertThat(noAuth.publishAs(bob, task("kept", TaskState.WORKING, 1, TTL), TaskRoute.NONE)
                        .ownerKey())
                .as("so on a server without authentication the id is free again")
                .isEqualTo("bob");
    }

    /** The store names no owner for the task "kept"; {@code noAuth} knows alice created it. */
    private void assertOnlyAliceReachesKept(TaskEngine noAuth, String stage) throws Exception {
        var connector = noAuth.connector();
        var bobsSession = new TestRoute("bob-session");
        connectorCalls.clear();

        assertThatThrownBy(() -> connector.get().apply(bob, get("kept")))
                .as("%s: no authentication never opens a task this engine knows the owner of", stage)
                .isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> connector.cancel().apply(bob, cancel("kept")))
                .isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> connector.get().apply(anonymous, get("kept")))
                .isInstanceOf(TaskNotFoundException.class);
        assertThat(connectorCalls)
                .as("%s: the owner this engine knows refuses before the connector runs", stage)
                .isEmpty();
        assertThatThrownBy(() -> noAuth.publishAs(bob, task("kept", TaskState.COMPLETED, 2, TTL), bobsSession))
                .as("%s: another caller cannot claim the id", stage)
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> noAuth.publishAs(anonymous, task("kept", TaskState.COMPLETED, 2, TTL), bobsSession))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> noAuth.publish(owned("kept", TaskState.COMPLETED, 2, "bob")))
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
        var noAuth = engine(true);
        store(task("removed", TaskState.COMPLETED, 1, TTL));
        store(task("without-ttl", TaskState.COMPLETED, 1, null));
        noAuth.publishAs(alice, task("kept", TaskState.COMPLETED, 1, TTL), TaskRoute.NONE);
        noAuth.publishAs(alice, task("removed", TaskState.COMPLETED, 1, TTL), TaskRoute.NONE);
        noAuth.publishAs(alice, task("without-ttl", TaskState.COMPLETED, 1, null), TaskRoute.NONE);
        clock.advance(KEEP_ALIVE.plusSeconds(1));
        noAuth.runJanitorSweep();

        assertThat(noAuth.publish(task("kept", TaskState.COMPLETED, 2, TTL)).ownerKey())
                .as("an application publish without an owner takes the retained one")
                .isEqualTo("alice");
        assertThat(noAuth.get("kept").ownerKey()).isEqualTo("alice");
        assertThat(noAuth.remove("removed")).as("no cached copy is left").isFalse();
        assertThat(noAuth.connector().get().apply(anonymous, get("removed")).ownerKey())
                .as("removing a task forgets its retained owner")
                .isNull();
        assertThat(noAuth.publishAs(bob, task("removed", TaskState.WORKING, 1, TTL), TaskRoute.NONE)
                        .ownerKey())
                .isEqualTo("bob");
        assertThat(noAuth.connector().get().apply(anonymous, get("without-ttl")).taskId())
                .as("a task without a ttl is not retained: its store must name the owner")
                .isEqualTo("without-ttl");
    }

    @Test
    void anUnownedCachedTaskTakesTheOwnerItsConnectorOrApplicationNames() throws Exception {
        engine.publish(task("read", TaskState.WORKING, 1, null));
        engine.publish(task("published", TaskState.WORKING, 2, null));

        var read = engine.refresh(alice, owned("read", TaskState.COMPLETED, 2, "alice"));
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
        assertThat(engine.refresh(alice, owned("read", TaskState.COMPLETED, 3, "bob"))
                        .revision())
                .isEqualTo(2);
        assertThat(engine.get("read").ownerKey()).isEqualTo("alice");
        assertThat(pushed)
                .containsExactly(
                        "read@1 -> TaskRoute.NONE", "published@2 -> TaskRoute.NONE", "read@2 -> TaskRoute.NONE");
    }

    @Test
    void aListingHoldsTheCallersTasksAndThePublicOnes() throws Exception {
        start(engine, alice, task("alices", TaskState.WORKING, 1, null), TaskRoute.NONE);
        start(engine, bob, task("bobs", TaskState.WORKING, 1, null), TaskRoute.NONE);
        start(engine, anonymous, task("public", TaskState.WORKING, 1, null), TaskRoute.NONE);
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
    void aTaskNobodyOwnsIsHiddenFromEveryCallerUnlessTheServerHasNoAuthentication() throws Exception {
        store(task("unowned", TaskState.WORKING, 1, null));
        start(engine, alice, task("alices", TaskState.WORKING, 1, null), TaskRoute.NONE);
        var noAuth = engine(true).connector();
        var list = TaskListRequest.builder().limit(10).build();

        assertThatThrownBy(() -> engine.connector().get().apply(bob, get("unowned")))
                .isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> engine.connector().cancel().apply(bob, cancel("unowned")))
                .isInstanceOf(TaskNotFoundException.class);
        assertThat(connectorCalls).as("the refused cancel never ran").doesNotContain("cancel:unowned");
        assertThatThrownBy(() -> engine.connector().get().apply(anonymous, get("unowned")))
                .as("a caller let through as anonymous is no exception")
                .isInstanceOf(TaskNotFoundException.class);

        assertThat(noAuth.get().apply(bob, get("unowned")).taskId()).isEqualTo("unowned");
        assertThat(noAuth.get().apply(anonymous, get("unowned")).taskId())
                .as("without authentication every caller reaches what nobody owns")
                .isEqualTo("unowned");
        assertThat(ids(noAuth.list().apply(bob, list))).containsExactly("unowned");
        assertThatThrownBy(() -> noAuth.get().apply(bob, get("alices")))
                .as("no authentication never opens an owned task")
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void theOwnerOfATaskNeverChanges() {
        var alicesSession = new TestRoute("alice-session");
        var bobsSession = new TestRoute("bob-session");
        start(engine, alice, task("owned", TaskState.WORKING, 1, null), alicesSession);
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

        var noAuth = engine(true);
        noAuth.publish(task("unowned", TaskState.WORKING, 1, null));
        assertThatThrownBy(() -> noAuth.publishAs(alice, task("unowned", TaskState.COMPLETED, 2, null), alicesSession))
                .as("no authentication does not let a named caller claim a task nobody owns")
                .isInstanceOf(IllegalStateException.class);
        var opened = noAuth.publishAs(anonymous, task("unowned", TaskState.COMPLETED, 2, null), bobsSession);
        assertThat(opened.revision())
                .as("where everyone reaches it anyway, an anonymous caller publishes: worker first, tool second")
                .isEqualTo(2);
        assertThat(opened.ownerKey()).as("which makes the task public").isEqualTo(TaskSnapshot.PUBLIC_OWNER_KEY);
        assertThatThrownBy(() -> noAuth.publish(owned("unowned", TaskState.COMPLETED, 3, "alice")))
                .as("and public it stays")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aConnectorReadNeverChangesTheCachedOwner() throws Exception {
        start(engine, alice, task("owned", TaskState.WORKING, 1, null), TaskRoute.NONE);

        var unstamped = engine.refresh(alice, task("owned", TaskState.WORKING, 2, null));
        var foreign = engine.refresh(alice, owned("owned", TaskState.WORKING, 3, "bob"));

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
                .as("nobody owns the task when the wait starts, and the server has no authentication")
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
    void aTaskOwnedWhileACallerWaitedIsNotHandedToTheWaiter() throws Exception {
        var never = new CompletableFuture<Void>();
        var self = new AtomicReference<TaskEngine>();
        var claimedMeanwhile = new TaskEngine(
                new TaskEngineSettings(
                        TaskConnector.builder()
                                .get((ctx, request) -> task(request.taskId(), TaskState.WORKING, 1, null))
                                .cancel((ctx, request) -> {})
                                .update((ctx, request) -> {})
                                .awaitResult((ctx, request) -> {
                                    self.get().publish(owned(request.taskId(), TaskState.COMPLETED, 2, "alice"));
                                    return task(request.taskId(), TaskState.COMPLETED, 1, null);
                                })
                                .build(),
                        Pagination.DEFAULT_PAGE_SIZE,
                        KEEP_ALIVE,
                        null,
                        TaskEngineSettings.DEFAULT_RESULT_POLL_INTERVAL,
                        true),
                clock);
        self.set(claimedMeanwhile);

        assertThatThrownBy(() -> claimedMeanwhile.awaitResult(bob, awaitResult("legacy-wait"), never))
                .as("alice owns the task by the time the wait ends, so bob does not get her cached snapshot")
                .isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> claimedMeanwhile.connector().awaitResult().apply(bob, awaitResult("hook-wait")))
                .as("nor the result the hook returns, though it names no owner")
                .isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> claimedMeanwhile.refresh(bob, task("legacy-wait", TaskState.COMPLETED, 1, null)))
                .as("a read bob made before alice owned the task does not return her cached snapshot")
                .isInstanceOf(TaskNotFoundException.class);
        var alices = claimedMeanwhile.awaitResult(alice, awaitResult("legacy-wait"), never);
        assertThat(alices.revision()).isEqualTo(2);
        assertThat(alices.ownerKey()).isEqualTo("alice");
    }

    @Test
    void aWaitResultThatNamesNoOwnerIsUnownedLikeAnyStoredSnapshot() throws Exception {
        var never = new CompletableFuture<Void>();
        var forgetful = TaskConnector.builder()
                .get((ctx, request) -> owned(request.taskId(), TaskState.WORKING, 1, "alice"))
                .cancel((ctx, request) -> {})
                .update((ctx, request) -> {})
                .awaitResult((ctx, request) -> task(request.taskId(), TaskState.COMPLETED, 2, null))
                .build();
        var secured = engine(forgetful, false);
        var noAuth = engine(forgetful, true);

        assertThat(secured.connector().get().apply(alice, get("waited")).ownerKey())
                .as("the store names alice when the wait starts")
                .isEqualTo("alice");
        assertThatThrownBy(() -> secured.connector().awaitResult().apply(alice, awaitResult("waited")))
                .as("with authentication a result that names no owner is nobody's, as it is from get")
                .isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> secured.awaitResult(alice, awaitResult("waited"), never))
                .as("the legacy tasks/result path goes through the same check")
                .isInstanceOf(TaskNotFoundException.class);
        assertThat(secured.get("waited")).as("a refused wait caches nothing").isNull();
        var shared = noAuth.connector().awaitResult().apply(alice, awaitResult("waited"));
        assertThat(shared.status())
                .as("a server without authentication gives an unowned result to its caller")
                .isEqualTo(TaskState.COMPLETED);
        assertThat(shared.revision()).isEqualTo(2);
        assertThat(shared.ownerKey()).isNull();
    }

    @Test
    void aToolCannotClaimATaskItsConnectorDoesNotReturnForItsCaller() throws Exception {
        assertThatThrownBy(() ->
                        engine.publishAs(bob, task("absent", TaskState.WORKING, 1, null), new TestRoute("bob-session")))
                .as("unknown to the connector or hidden from bob: the engine cannot tell, so it is not bob's")
                .isInstanceOf(IllegalStateException.class);
        assertThat(engine.get("absent")).as("a refused claim caches nothing").isNull();
        assertThat(pushed).isEmpty();

        var created = start(engine, alice, task("absent", TaskState.WORKING, 1, null), TaskRoute.NONE);

        assertThat(created.ownerKey())
                .as("the refused claim does not hold the id against its creator")
                .isEqualTo("alice");
        assertThat(engine.connector().get().apply(alice, get("absent")).taskId())
                .isEqualTo("absent");
        assertThat(engine(true)
                        .publishAs(anonymous, task("new", TaskState.WORKING, 1, null), TaskRoute.NONE)
                        .ownerKey())
                .as(
                        "a server without authentication has no caller to protect: a task its connector does not know is new")
                .isEqualTo(TaskSnapshot.PUBLIC_OWNER_KEY);
    }

    @Test
    void removingATaskDropsOnlyTheCachedCopy() throws Exception {
        start(engine, alice, task("owned", TaskState.WORKING, 1, TTL), TaskRoute.NONE);

        assertThat(engine.remove("owned")).isTrue();

        assertThat(engine.get("owned")).isNull();
        assertThatThrownBy(() -> engine.connector().get().apply(bob, get("owned")))
                .as("the stored task keeps its owner")
                .isInstanceOf(TaskNotFoundException.class);
    }

    private TaskEngine engine(boolean unauthenticated) {
        return engine(storingConnector(), unauthenticated);
    }

    private TaskEngine engine(TaskConnector connector, boolean unauthenticated) {
        return new TaskEngine(
                new TaskEngineSettings(
                        connector,
                        Pagination.DEFAULT_PAGE_SIZE,
                        KEEP_ALIVE,
                        null,
                        TaskEngineSettings.DEFAULT_RESULT_POLL_INTERVAL,
                        unauthenticated),
                clock);
    }

    /** Starts a task as a tool must: stores it with its caller's owner, then hands it to the engine. */
    private TaskSnapshot start(TaskEngine engine, InteractionContext caller, TaskSnapshot snapshot, TaskRoute route) {
        store(TaskSnapshot.builder()
                .from(snapshot)
                .ownerKey(TaskSnapshot.ownerKeyOf(caller.securityContext()))
                .build());
        return engine.publishAs(caller, snapshot, route);
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
