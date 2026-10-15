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
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Protocol-neutral, revision-aware cache of immutable task projections. Protocol bindings route push
 * traffic through {@link TaskEvents} and read the cache for their task methods; the connector stays
 * the authority on execution.
 *
 * <p><b>Ownership.</b> A task a caller creates ({@link #publishAs}) belongs to that caller, from any
 * of its sessions; a task nobody created through a caller ({@link #publish}) is unowned and left to
 * the connector. Every task call a caller makes goes through {@link #connector()}, which answers a
 * foreign caller exactly as it would an unknown id and never reaches the real connector. The owner
 * is bound for the task's own {@code ttl}, even after its snapshot left the cache; a task with no
 * {@code ttl} is bound while its snapshot is cached. Bindings are process-local: another node, or
 * this one after a restart, treats the task as unowned, and only the connector can still refuse the
 * caller.
 */
public final class TaskEngine implements Tasks {

    private static final Logger logger = LoggerFactory.getLogger(TaskEngine.class);
    private static final Duration JANITOR_INTERVAL = Duration.ofSeconds(30);

    private final ConcurrentHashMap<String, TaskEntry> entries = new ConcurrentHashMap<>();
    /**
     * Who owns each task a named caller created. Created in the same step as the task and outliving
     * its snapshot: see {@link Binding}. Unowned tasks have no binding.
     */
    private final ConcurrentHashMap<String, Binding> owners = new ConcurrentHashMap<>();

    private final TaskEngineSettings settings;
    private final TaskConnector guardedConnector;
    private final Clock clock;
    private final List<TaskEvents> listeners = new CopyOnWriteArrayList<>();

    /**
     * The owner of one task. While its snapshot is cached {@code until} is {@code null}: the binding
     * lives and dies with the entry. When the janitor evicts the snapshot of a task that has a
     * {@code ttl}, the binding stays until {@code createdAt + ttl}, so an evicted task is still hidden
     * from other callers for as long as it can be reached; a task without a {@code ttl} loses its
     * binding with its snapshot.
     */
    private record Binding(Owner.Named owner, @Nullable Instant until) {}

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
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
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
     * naming a task owned by someone else fails with {@link TaskNotFoundException}, the exception an
     * unknown id raises, before the real connector runs, and a listing leaves such tasks out. The
     * real connector is reachable from nowhere else in this engine.
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
                    requireVisibleTo(ctx, request.taskId());
                    return connector.get().apply(ctx, request);
                })
                .cancel((ctx, request) -> {
                    requireVisibleTo(ctx, request.taskId());
                    connector.cancel().apply(ctx, request);
                })
                .update((ctx, request) -> {
                    requireVisibleTo(ctx, request.taskId());
                    connector.update().apply(ctx, request);
                });
        var list = connector.list();
        if (list != null) {
            guarded.list((ctx, request) -> {
                var page = list.apply(ctx, request);
                var visible = page.items().stream()
                        .filter(snapshot -> isVisibleTo(ctx, snapshot.taskId()))
                        .toList();
                return PaginatedResult.of(visible, page.nextCursor(), page.cursorValid());
            });
        }
        var awaitResult = connector.awaitResult();
        if (awaitResult != null) {
            guarded.awaitResult((ctx, request) -> {
                requireVisibleTo(ctx, request.taskId());
                return awaitResult.apply(ctx, request);
            });
        }
        return guarded.build();
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
     * application publishing through {@code TachyonServer#tasks()}. It claims nothing, so it updates
     * a task whoever owns it, and an unknown task it creates is unowned. A task call that read a
     * snapshot from the connector uses {@link #refresh} instead.
     *
     * @param snapshot the task projection
     * @return the effective snapshot
     */
    @Override
    public TaskSnapshot publish(TaskSnapshot snapshot) {
        var effective = withDefaults(Objects.requireNonNull(snapshot, "snapshot"));
        return settle(place(effective, TaskRoute.NONE), effective, TaskRoute.NONE);
    }

    /**
     * Updates the cached task with {@code snapshot} if it is newer, for a task call that read the
     * snapshot from the connector. A read never creates a task: an id the engine does not hold is not
     * cached, so a foreign or early read cannot leave it unowned before its creator publishes it.
     *
     * @param snapshot the connector's projection
     * @return the cached snapshot, or {@code snapshot} with the engine defaults if the task is not
     *     cached
     */
    public TaskSnapshot refresh(TaskSnapshot snapshot) {
        var effective = withDefaults(Objects.requireNonNull(snapshot, "snapshot"));
        var entry = entries.get(effective.taskId());
        return entry == null ? effective : settle(new Placed(entry, false), effective, TaskRoute.NONE);
    }

    /**
     * Caches {@code snapshot} for a task a caller creates, routing its push traffic along
     * {@code route} and binding the task to the caller of {@code ctx}. An unrouted entry takes the
     * route; a routed one keeps its own. The owner never changes: the caller must be the task's
     * owner, so a task owned by someone else, and a task nobody owns, are refused before anything
     * about them changes.
     *
     * @param ctx the context of the creating request, which fixes the caller
     * @param snapshot the task projection
     * @param route where push traffic goes, or {@link TaskRoute#NONE}
     * @return the effective snapshot
     * @throws IllegalStateException if the task already has a different owner
     */
    public TaskSnapshot publishAs(InteractionContext ctx, TaskSnapshot snapshot, TaskRoute route) {
        var caller = Owner.of(ctx.securityContext());
        var effective = withDefaults(Objects.requireNonNull(snapshot, "snapshot"));
        Objects.requireNonNull(route, "route");
        var binding = bind(effective, caller);
        var placed = place(effective, route);
        if (binding != null && !placed.created()) {
            owners.remove(effective.taskId(), binding);
            throw refused(effective);
        }
        return settle(placed, effective, route);
    }

    private record Placed(TaskEntry entry, boolean created) {}

    private Placed place(TaskSnapshot effective, TaskRoute route) {
        var created = new TaskEntry(effective, route, settings.keepAlive(), clock);
        var existing = entries.putIfAbsent(effective.taskId(), created);
        return existing == null ? new Placed(created, true) : new Placed(existing, false);
    }

    private TaskSnapshot settle(Placed placed, TaskSnapshot effective, TaskRoute route) {
        var entry = placed.entry();
        if (placed.created()) {
            entry.notifyIfNewer(this::fireStatus);
            return effective;
        }
        entry.route(route);
        if (entry.publish(effective)) {
            entry.notifyIfNewer(this::fireStatus);
        }
        return entry.snapshot();
    }

    /**
     * Makes {@code caller} the owner of the task, atomically: it creates the binding of a named caller
     * for a task nobody has, confirms it for the owner, and refuses everyone else. A named caller
     * cannot claim a task that is already cached unowned, and an anonymous caller cannot publish
     * on an owned one.
     *
     * @return the binding this call created, or {@code null} if it created none
     */
    private @Nullable Binding bind(TaskSnapshot effective, Owner caller) {
        var created = new Binding[1];
        owners.compute(effective.taskId(), (id, current) -> {
            var live = current != null && !isOver(current) ? current : null;
            if (live != null) {
                if (!live.owner().equals(caller)) {
                    throw refused(effective);
                }
                return live;
            }
            if (!(caller instanceof Owner.Named named)) {
                return null;
            }
            if (entries.containsKey(id)) {
                throw refused(effective);
            }
            created[0] = new Binding(named, null);
            return created[0];
        });
        return created[0];
    }

    private static IllegalStateException refused(TaskSnapshot snapshot) {
        var taskId = snapshot.taskId();
        logger.warn(
                "Refused a publish claiming task {} for a caller that does not own it",
                taskId.substring(0, Math.min(8, taskId.length() / 2)) + "…");
        return new IllegalStateException("Task ownership conflict");
    }

    private boolean isOver(Binding binding) {
        var until = binding.until();
        return until != null && !clock.instant().isBefore(until);
    }

    /**
     * Whether {@code caller} may reach the task: it owns it, or nobody owns it, so access is the
     * connector's decision. The one answer behind {@link #connector()} and the streams that follow a
     * task; a lookup in memory, safe to ask for every event.
     *
     * @param caller the caller asking, as {@link Owner#of} derives it from a request
     * @param taskId the task id, known or not
     * @return {@code true} unless the task is bound to a different owner
     */
    public boolean admits(Owner caller, String taskId) {
        var binding = owners.get(taskId);
        return binding == null || isOver(binding) || binding.owner().admits(caller);
    }

    private boolean isVisibleTo(InteractionContext ctx, String taskId) {
        return admits(Owner.of(ctx.securityContext()), taskId);
    }

    private void requireVisibleTo(InteractionContext ctx, String taskId) throws TaskNotFoundException {
        if (!isVisibleTo(ctx, taskId)) {
            logger.debug("Hiding an owned task from another caller");
            throw new TaskNotFoundException(taskId);
        }
    }

    private void fireStatus(TaskSnapshot snapshot, TaskRoute route) {
        for (var listener : listeners) {
            listener.onStatus(snapshot, route);
        }
    }

    /**
     * Applies engine defaults ({@code pollInterval}) without touching the cache.
     *
     * @param snapshot the task projection
     * @return {@code snapshot} with defaults applied
     */
    public TaskSnapshot withDefaults(TaskSnapshot snapshot) {
        var pollInterval = settings.pollInterval();
        return pollInterval == null || snapshot.pollInterval() != null
                ? snapshot
                : TaskSnapshot.builder()
                        .from(snapshot)
                        .pollInterval(pollInterval)
                        .build();
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
     * Returns the ids of {@code taskIds} that {@code ctx} may read: not owned by someone else, and
     * readable as the connector's {@code get} decides. Fails closed: an id the connector refuses or
     * fails on is left out, and an owned task is left out without asking the connector. Caches
     * nothing. Blocks on the connector, so never call it on an event loop.
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
        owners.remove(taskId);
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
        for (var cached : entries.entrySet()) {
            var taskId = cached.getKey();
            var entry = cached.getValue();
            entry.evictIfExpired(() -> evict(taskId, entry));
        }
        owners.values().removeIf(this::isOver);
    }

    /**
     * Drops an expired snapshot. The owner is still bound while the entry is being removed, so
     * nothing sees the task unowned in between: a binding is then cut to the task's own {@code ttl},
     * or dropped for a task without one.
     */
    private boolean evict(String taskId, TaskEntry entry) {
        if (!entries.remove(taskId, entry)) {
            return false;
        }
        var last = entry.snapshot();
        owners.computeIfPresent(taskId, (id, binding) -> {
            if (binding.until() != null) {
                return binding;
            }
            var ttl = last.ttl();
            if (ttl == null) {
                return null;
            }
            var until = last.createdAt().plus(ttl);
            return clock.instant().isBefore(until) ? new Binding(binding.owner(), until) : null;
        });
        return true;
    }
}
