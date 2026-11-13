/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.tasks.engine;

import dev.tachyonmcp.api.annotations.LegacyApi;
import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.features.PaginatedResult;
import dev.tachyonmcp.api.server.features.tasks.TaskAwaitResultRequest;
import dev.tachyonmcp.api.server.features.tasks.TaskCancelRequest;
import dev.tachyonmcp.api.server.features.tasks.TaskConnector;
import dev.tachyonmcp.api.server.features.tasks.TaskGetRequest;
import dev.tachyonmcp.api.server.features.tasks.TaskNotFoundException;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.api.server.features.tasks.TaskState;
import dev.tachyonmcp.api.server.features.tasks.Tasks;
import dev.tachyonmcp.core.server.internal.AbstractJanitor;
import dev.tachyonmcp.core.server.security.Owner;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Protocol-neutral, revision-aware cache of immutable task projections. Protocol bindings route push
 * traffic through {@link TaskEvents} and read the cache for their task methods; the connector stays
 * the authority on execution.
 *
 * <p><b>Ownership.</b> A task a caller creates ({@link #publishAs}) belongs to that caller, from any
 * of its sessions: its {@link TaskSnapshot#ownerKey()} names it, and never changes. The owner lives
 * on the snapshot, so it lives wherever the connector stores the task: after a restart, and on every
 * node. This engine also remembers the owner of a task it evicted until the task's {@code ttl}, so a
 * connector that stores no owner keeps the task with its owner for that long; a task without a
 * {@code ttl}, and any task after a restart, has only the owner the connector stores. Every task
 * call a caller makes goes through {@link #connector()}, which answers a foreign caller exactly as
 * it would an unknown id; an owner this engine knows refuses before the real connector runs,
 * otherwise the connector's own snapshot names the owner. A task with no owner anywhere reaches
 * anonymous callers only, unless {@link TaskEngineSettings#shareUnownedTasks()}.
 */
public final class TaskEngine implements Tasks {

    private static final Logger logger = LoggerFactory.getLogger(TaskEngine.class);
    private static final Duration JANITOR_INTERVAL = Duration.ofSeconds(30);

    private final ConcurrentHashMap<String, TaskEntry> entries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Retained> retained = new ConcurrentHashMap<>();

    /** The owner of an evicted task, kept until the task's ttl so nobody else can claim its id. */
    private record Retained(String ownerKey, Instant until) {}

    private final TaskEngineSettings settings;
    private final TaskConnector guardedConnector;
    private final Clock clock;
    private final UnaryOperator<TaskSnapshot> normalizer;
    private final List<TaskEvents> listeners = new CopyOnWriteArrayList<>();

    private final AbstractJanitor janitor = new AbstractJanitor("task-janitor") {
        @Override
        protected void sweep() {
            runJanitorSweep();
        }
    };

    /**
     * Creates an engine. Call {@link #start()} to begin evicting expired projections.
     *
     * @param settings the engine settings
     * @param clock the clock for retention and timestamps
     */
    public TaskEngine(TaskEngineSettings settings, Clock clock) {
        this(settings, clock, UnaryOperator.identity());
    }

    /**
     * Creates an engine that passes every snapshot it caches or returns through {@code normalizer}
     * after applying engine defaults; a protocol binding uses it to encode completed results the way
     * its wire format expects. Call {@link #start()} to begin evicting expired projections.
     *
     * @param settings the engine settings
     * @param clock the clock for retention and timestamps
     * @param normalizer the rewrite applied by {@link #withDefaults(TaskSnapshot)}
     */
    public TaskEngine(TaskEngineSettings settings, Clock clock, UnaryOperator<TaskSnapshot> normalizer) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.normalizer = Objects.requireNonNull(normalizer, "normalizer");
        this.guardedConnector = guard(settings.connector());
    }

    /**
     * Returns the engine settings.
     *
     * @return the settings
     */
    public TaskEngineSettings settings() {
        return settings;
    }

    /**
     * Returns the connector to the system that owns task execution, as a caller may use it: a call
     * naming a task the caller may not reach fails with {@link TaskNotFoundException}, the exception
     * an unknown id raises, and a listing leaves such tasks out. An owner this engine knows, cached
     * or remembered since eviction, refuses before the real connector runs; for a task whose owner it
     * does not know, {@code cancel}, {@code update} and {@code awaitResult} first read the task with
     * {@code get} to learn it. The real connector is reachable from nowhere else in this engine.
     *
     * @return the guarded connector
     */
    public TaskConnector connector() {
        return guardedConnector;
    }

    @SuppressWarnings("deprecation")
    private TaskConnector guard(TaskConnector connector) {
        var guarded = TaskConnector.builder()
                .get((ctx, request) -> {
                    var caller = Owner.of(ctx.securityContext());
                    var known = knownOwner(request.taskId());
                    if (known != null) {
                        require(admits(caller, known, null), request.taskId());
                    }
                    var snapshot = connector.get().apply(ctx, request);
                    require(admits(caller, known, snapshot.ownerKey()), request.taskId());
                    return snapshot;
                })
                .cancel((ctx, request) -> {
                    requireReachable(connector, ctx, request.taskId(), request.meta());
                    connector.cancel().apply(ctx, request);
                })
                .update((ctx, request) -> {
                    requireReachable(connector, ctx, request.taskId(), request.meta());
                    connector.update().apply(ctx, request);
                });
        var list = connector.list();
        if (list != null) {
            guarded.list((ctx, request) -> {
                var caller = Owner.of(ctx.securityContext());
                var page = list.apply(ctx, request);
                var visible = page.items().stream()
                        .filter(snapshot -> admits(caller, knownOwner(snapshot.taskId()), snapshot.ownerKey()))
                        .toList();
                return PaginatedResult.of(visible, page.nextCursor(), page.cursorValid());
            });
        }
        var awaitResult = connector.awaitResult();
        if (awaitResult != null) {
            guarded.awaitResult((ctx, request) -> {
                requireReachable(connector, ctx, request.taskId(), request.meta());
                var result = awaitResult.apply(ctx, request);
                var caller = Owner.of(ctx.securityContext());
                require(admits(caller, knownOwner(request.taskId()), result.ownerKey()), request.taskId());
                return result;
            });
        }
        return guarded.build();
    }

    /**
     * Refuses {@code ctx}'s caller a task it may not reach, before an operation that changes or waits
     * on it: from the owner this engine knows if there is one, otherwise from the connector's {@code
     * get}.
     */
    private void requireReachable(
            TaskConnector connector, InteractionContext ctx, String taskId, @Nullable Map<String, Object> meta)
            throws Exception {
        var caller = Owner.of(ctx.securityContext());
        var known = knownOwner(taskId);
        if (known != null) {
            require(admits(caller, known, null), taskId);
            return;
        }
        var stored = connector.get().apply(ctx, getRequest(taskId, meta));
        require(admits(caller, null, stored.ownerKey()), taskId);
    }

    /** The owner of the cached task, or the one remembered since its eviction. */
    private @Nullable String knownOwner(String taskId) {
        var entry = entries.get(taskId);
        return entry != null ? entry.snapshot().ownerKey() : retainedOwner(taskId);
    }

    private @Nullable String retainedOwner(String taskId) {
        var kept = retained.get(taskId);
        return kept != null && clock.instant().isBefore(kept.until()) ? kept.ownerKey() : null;
    }

    /**
     * Whether {@code caller} may reach a task whose owner this engine and the connector name. Every
     * owner named must be the caller; with none named, the task is unowned.
     */
    private boolean admits(Owner caller, @Nullable String known, @Nullable String stored) {
        if (known == null && stored == null) {
            return settings.shareUnownedTasks() || caller.key() == null;
        }
        return (known == null || known.equals(caller.key())) && (stored == null || stored.equals(caller.key()));
    }

    private static void require(boolean admitted, String taskId) throws TaskNotFoundException {
        if (!admitted) {
            logger.debug("Hiding a task from a caller that may not reach it");
            throw new TaskNotFoundException(taskId);
        }
    }

    /**
     * Registers a push-traffic listener, typically a protocol binding.
     *
     * @param listener the listener
     */
    public void addListener(TaskEvents listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Caches {@code snapshot} if it is unknown or newer, for a publish that has no caller: the
     * application publishing through {@code TachyonServer#tasks()}. The owner of a task never
     * changes: a snapshot without a {@link TaskSnapshot#ownerKey()} keeps the owner this engine knows,
     * a snapshot naming one gives it to a task nobody owns yet, and a snapshot naming another owner
     * is refused. A task call that read a snapshot from the connector uses {@link #refresh} instead.
     *
     * @param snapshot the task projection
     * @return the effective snapshot
     * @throws IllegalStateException if {@code snapshot} names an owner other than the task's
     */
    @Override
    public TaskSnapshot publish(TaskSnapshot snapshot) {
        var effective = withDefaults(Objects.requireNonNull(snapshot, "snapshot"));
        var entry = place(effective, TaskRoute.NONE, false);
        if (entry == null || !entry.publish(effective)) {
            throw refused(effective);
        }
        entry.notifyIfNewer(this::fireStatus);
        return entry.snapshot();
    }

    /**
     * Updates the cached task with {@code snapshot} if it is newer, for a task call that read the
     * snapshot from the connector. A read never creates a task: an id the engine does not hold is not
     * cached, so a foreign or early read cannot leave it unowned before its creator publishes it. A
     * snapshot without an owner keeps the cached one, a snapshot naming one gives it to a cached task
     * nobody owns yet, and a snapshot naming another owner is not cached.
     *
     * @param snapshot the connector's projection
     * @return the cached snapshot, or {@code snapshot} with the engine defaults if the task is not
     *     cached
     */
    public TaskSnapshot refresh(TaskSnapshot snapshot) {
        var effective = withDefaults(Objects.requireNonNull(snapshot, "snapshot"));
        var entry = entries.get(effective.taskId());
        if (entry == null) {
            return effective;
        }
        if (entry.publish(effective)) {
            entry.notifyIfNewer(this::fireStatus);
        } else {
            logger.debug("Not caching a read of task {} that names another owner", effective.taskId());
        }
        return entry.snapshot();
    }

    /**
     * Caches {@code snapshot} for a task a caller creates, routing its push traffic along
     * {@code route} and stamping the caller of {@code ctx} on it as its {@link TaskSnapshot#ownerKey()
     * owner}. An unrouted entry takes the route; a routed one keeps its own. The owner never changes:
     * the caller must be the task's owner, so a task owned by someone else, cached or evicted before
     * its {@code ttl}, a cached task nobody owns, and a snapshot naming another owner are refused
     * before anything about them changes.
     *
     * @param ctx the context of the creating request, which fixes the caller
     * @param snapshot the task projection
     * @param route where push traffic goes, or {@link TaskRoute#NONE}
     * @return the effective snapshot, carrying the caller's owner key
     * @throws IllegalStateException if the task already has a different owner
     */
    public TaskSnapshot publishAs(InteractionContext ctx, TaskSnapshot snapshot, TaskRoute route) {
        var key = Owner.of(ctx.securityContext()).key();
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(route, "route");
        if (snapshot.ownerKey() != null && !snapshot.ownerKey().equals(key)) {
            throw refused(snapshot);
        }
        var effective = withDefaults(TaskEntry.withOwner(snapshot, key));
        var entry = place(effective, route, true);
        if (entry == null || !Objects.equals(entry.snapshot().ownerKey(), key)) {
            throw refused(effective);
        }
        entry.route(route);
        entry.publish(effective);
        entry.notifyIfNewer(this::fireStatus);
        return entry.snapshot();
    }

    /**
     * Returns the task's entry, created from {@code effective} if absent, or {@code null} when the
     * owner remembered since the task's eviction refuses to create it: for a snapshot naming another
     * owner, and for any other caller's {@code claim}, even an anonymous one. A snapshot the
     * application publishes without an owner takes the remembered one.
     */
    private @Nullable TaskEntry place(TaskSnapshot effective, TaskRoute route, boolean claim) {
        return entries.computeIfAbsent(effective.taskId(), taskId -> {
            var kept = retainedOwner(taskId);
            var claimed = effective.ownerKey();
            if (kept != null && !kept.equals(claimed) && (claim || claimed != null)) {
                return null;
            }
            var owned = TaskEntry.withOwner(effective, kept != null ? kept : claimed);
            return new TaskEntry(owned, route, settings.keepAlive(), clock);
        });
    }

    private static IllegalStateException refused(TaskSnapshot snapshot) {
        logger.warn(
                "Refused a publish claiming task {} for a caller that does not own it",
                Owner.truncated(snapshot.taskId()));
        return new IllegalStateException("Task ownership conflict");
    }

    /**
     * Whether a stream {@code caller} opened still follows the task after {@code snapshot}: the owner
     * it names must be the caller. A snapshot naming no owner goes to every stream, because {@link
     * #readableTaskIds} already decided who may read the task when the stream opened. Safe to ask for
     * every event.
     *
     * @param caller the caller listening, as {@link Owner#of} derives it from a request
     * @param snapshot the task, as cached
     * @return whether the stream receives it
     */
    public boolean admits(Owner caller, TaskSnapshot snapshot) {
        var owner = snapshot.ownerKey();
        return owner == null || owner.equals(caller.key());
    }

    private void fireStatus(TaskSnapshot snapshot, TaskRoute route) {
        for (var listener : listeners) {
            listener.onStatus(snapshot, route);
        }
    }

    /**
     * Applies engine defaults ({@code pollInterval}) and the binding's normalizer, without touching
     * the cache. Every snapshot the engine caches or returns passes through here.
     *
     * @param snapshot the task projection
     * @return {@code snapshot} with defaults applied
     */
    public TaskSnapshot withDefaults(TaskSnapshot snapshot) {
        var pollInterval = settings.pollInterval();
        var effective = pollInterval == null || snapshot.pollInterval() != null
                ? snapshot
                : TaskSnapshot.builder()
                        .from(snapshot)
                        .pollInterval(pollInterval)
                        .build();
        return normalizer.apply(effective);
    }

    /**
     * Blocks until the task is terminal and returns its snapshot, for the legacy blocking result.
     * Uses the connector's {@code awaitResult} when set, which owns its own wait bound; otherwise
     * polls {@code get} as described for {@link #cancelAndAwait}. Blocks, so never call it on an event
     * loop.
     *
     * @param ctx the caller, passed to the connector
     * @param request the result request
     * @param responseUndeliverable completes once the response can no longer be delivered; ends a polled
     *     wait without touching the task
     * @return the terminal snapshot
     * @throws TaskNotFoundException if the task is owned by someone else, or the connector does not
     *     know it or hides it from {@code ctx}
     * @throws TaskAwaitException if the task's {@code ttl} elapsed before it became terminal
     * @throws CancellationException if {@code responseUndeliverable} completed first
     * @throws InterruptedException if interrupted while waiting between polls
     * @throws Exception if the connector fails
     */
    @LegacyApi
    public TaskSnapshot awaitResult(
            InteractionContext ctx, TaskAwaitResultRequest request, CompletionStage<Void> responseUndeliverable)
            throws Exception {
        var awaitHook = connector().awaitResult();
        if (awaitHook != null) {
            return refresh(awaitHook.apply(ctx, request));
        }
        return pollUntilTerminal(ctx, getRequest(request.taskId(), request.meta()), responseUndeliverable);
    }

    /**
     * Cancels a task and blocks until it is {@code cancelled}, for the legacy synchronous cancel.
     * The connector's cancel may settle later; this polls {@code get}, refreshing the cache with every snapshot and
     * sleeping for its {@code pollInterval}, or {@link TaskEngineSettings#resultPollInterval()} when
     * it suggests none, until the task is terminal, its {@code ttl} elapses, or {@code responseUndeliverable}
     * completes.
     * Blocks, so never call it on an event loop.
     *
     * @param ctx the caller, passed to the connector
     * @param request the cancel request
     * @param responseUndeliverable completes once the response can no longer be delivered; ends the wait,
     *     the cancel already requested
     * @return the {@code cancelled} snapshot
     * @throws TaskNotFoundException if the task is owned by someone else, or the connector does not
     *     know it or hides it from {@code ctx}
     * @throws TaskAwaitException {@link TaskAwaitException.Reason#TERMINAL} if the task was already
     *     terminal, when the connector's cancel is not called, or settled in another terminal status;
     *     otherwise as for {@link #awaitResult}
     * @throws InterruptedException if interrupted while waiting between polls
     * @throws Exception if the connector fails
     */
    @LegacyApi
    public TaskSnapshot cancelAndAwait(
            InteractionContext ctx, TaskCancelRequest request, CompletionStage<Void> responseUndeliverable)
            throws Exception {
        var connector = connector();
        var getRequest = getRequest(request.taskId(), request.meta());
        var current = refresh(connector.get().apply(ctx, getRequest));
        if (current.status().isTerminal()) {
            throw TaskAwaitException.terminal(current.status());
        }
        connector.cancel().apply(ctx, request);
        var settled = pollUntilTerminal(ctx, getRequest, responseUndeliverable);
        if (settled.status() != TaskState.CANCELLED) {
            logger.debug("Cancel of task {} lost to terminal status {}", request.taskId(), settled.status());
            throw TaskAwaitException.terminal(settled.status());
        }
        return settled;
    }

    private TaskSnapshot pollUntilTerminal(
            InteractionContext ctx, TaskGetRequest getRequest, CompletionStage<Void> responseUndeliverable)
            throws Exception {
        var undeliverable = responseUndeliverable.toCompletableFuture();
        var get = connector().get();
        while (true) {
            var snapshot = refresh(get.apply(ctx, getRequest));
            if (snapshot.status().isTerminal()) {
                return snapshot;
            }
            var pause = snapshot.pollInterval() != null ? snapshot.pollInterval() : settings.resultPollInterval();
            var ttl = snapshot.ttl();
            if (ttl != null) {
                var untilExpiry =
                        Duration.between(clock.instant(), snapshot.createdAt().plus(ttl));
                if (!untilExpiry.isPositive()) {
                    throw TaskAwaitException.expired(getRequest.taskId());
                }
                if (untilExpiry.compareTo(pause) < 0) {
                    pause = untilExpiry;
                }
            }
            try {
                undeliverable.get(pause.toNanos(), TimeUnit.NANOSECONDS);
                logger.debug("Response undeliverable, stopped waiting on task {}", getRequest.taskId());
                throw new CancellationException("Response undeliverable");
            } catch (TimeoutException e) {
                // no terminal status yet: poll again
            }
        }
    }

    private static TaskGetRequest getRequest(String taskId, @Nullable Map<String, Object> meta) {
        return TaskGetRequest.builder().taskId(taskId).meta(meta).build();
    }

    /**
     * Returns the ids of {@code taskIds} that {@code ctx} may read, as {@link #connector()}'s {@code
     * get} decides. Fails closed: an id the connector refuses or fails on is left out, and a task
     * this engine knows as someone else's is left out without asking the connector. Caches nothing.
     * Blocks on the connector, so never call it on an event loop.
     *
     * @param ctx the caller
     * @param taskIds the requested task ids
     * @return the readable subset
     */
    public Set<String> readableTaskIds(InteractionContext ctx, Set<String> taskIds) {
        var connector = connector();
        var readable = new ArrayList<String>(taskIds.size());
        for (var taskId : taskIds) {
            try {
                connector
                        .get()
                        .apply(ctx, TaskGetRequest.builder().taskId(taskId).build());
                readable.add(taskId);
            } catch (TaskNotFoundException e) {
                logger.debug("Listener may not read task {}", taskId);
            } catch (InterruptedException e) {
                // E.g. executor shutdownNow(): stop asking the connector, keep only ids already allowed.
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.debug("Leaving task {} out of a listener: connector failed", taskId, e);
            }
        }
        return Set.copyOf(readable);
    }

    @Override
    public void reportProgress(String taskId, double progress, @Nullable Double total, @Nullable String message) {
        var entry = entries.get(taskId);
        if (entry == null) {
            logger.debug("Dropping task progress for unknown taskId={}", taskId);
            return;
        }
        var route = entry.route();
        for (var listener : listeners) {
            listener.onProgress(taskId, route, progress, total, message);
        }
    }

    @Override
    public @Nullable TaskSnapshot get(String taskId) {
        var entry = entries.get(taskId);
        return entry != null ? entry.snapshot() : null;
    }

    /**
     * Resolves a requested page size against the configured default.
     *
     * @param requestedLimit the requested size, or non-positive for the default
     * @return the effective page size
     */
    public int resolvePageLimit(int requestedLimit) {
        return requestedLimit > 0 ? requestedLimit : settings.pageSize();
    }

    @Override
    public boolean remove(String taskId) {
        var removed = entries.remove(taskId) != null;
        retained.remove(taskId);
        return removed;
    }

    /** Starts evicting expired projections in the background. */
    public void start() {
        janitor.start(JANITOR_INTERVAL);
    }

    /** Stops background eviction. */
    public void stop() {
        janitor.close();
    }

    void runJanitorSweep() {
        var now = clock.instant();
        retained.values().removeIf(kept -> !now.isBefore(kept.until()));
        for (var cached : entries.entrySet()) {
            var taskId = cached.getKey();
            var entry = cached.getValue();
            entry.evictIfExpired(() -> {
                var kept = retain(taskId, entry.snapshot(), now);
                var removed = entries.remove(taskId, entry);
                if (!removed && kept != null) {
                    retained.remove(taskId, kept);
                }
                return removed;
            });
        }
    }

    /** Remembers the owner of a task about to be evicted before its ttl, ahead of the eviction. */
    private @Nullable Retained retain(String taskId, TaskSnapshot evicted, Instant now) {
        var owner = evicted.ownerKey();
        var ttl = evicted.ttl();
        if (owner == null || ttl == null) {
            return null;
        }
        var until = evicted.createdAt().plus(ttl);
        if (!now.isBefore(until)) {
            return null;
        }
        var kept = new Retained(owner, until);
        retained.put(taskId, kept);
        return kept;
    }
}
