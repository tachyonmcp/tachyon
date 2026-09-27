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
import dev.tachyonmcp.core.server.features.ChangeSupport;
import dev.tachyonmcp.core.server.features.Pagination;
import dev.tachyonmcp.core.server.internal.AbstractJanitor;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Protocol-neutral, revision-aware cache of immutable task projections. Protocol bindings route push
 * traffic through {@link TaskEvents} and read the cache for their task methods; the connector stays
 * the authority on execution and access.
 */
public final class TaskEngine implements Tasks {

    private static final Logger logger = LoggerFactory.getLogger(TaskEngine.class);
    private static final Duration JANITOR_INTERVAL = Duration.ofSeconds(30);

    private final ConcurrentHashMap<String, TaskEntry> entries = new ConcurrentHashMap<>();
    private final TaskEngineSettings settings;
    private final Clock clock;
    private final List<TaskEvents> listeners = new CopyOnWriteArrayList<>();
    private final ChangeSupport changes = new ChangeSupport();
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
     * Returns the connector to the system that owns task execution.
     *
     * @return the connector
     */
    public TaskConnector connector() {
        return settings.connector();
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
     * Registers a callback run whenever the cached task set changes: publish, remove, or eviction.
     *
     * @param callback the callback
     */
    public void onChange(Runnable callback) {
        changes.onChange(Objects.requireNonNull(callback, "callback"));
    }

    @Override
    public TaskSnapshot publish(TaskSnapshot snapshot) {
        return publish(snapshot, TaskRoute.NONE);
    }

    /**
     * Caches {@code snapshot} if it is unknown or newer, routing its push traffic along {@code route}.
     * An unrouted entry takes the route; a routed one keeps its own. Never refuses: access is the
     * connector's decision, not the cache's.
     *
     * @param snapshot the task projection
     * @param route where push traffic goes, or {@link TaskRoute#NONE}
     * @return the effective snapshot
     */
    public TaskSnapshot publish(TaskSnapshot snapshot, TaskRoute route) {
        var effective = withDefaults(Objects.requireNonNull(snapshot, "snapshot"));
        Objects.requireNonNull(route, "route");
        var created = new TaskEntry(effective, route, settings.keepAlive(), clock);
        var existing = entries.putIfAbsent(effective.taskId(), created);
        if (existing == null) {
            created.notifyIfNewer(this::fireStatus);
            changes.fireOnChange();
            return effective;
        }
        existing.route(route);
        return publish(existing, effective);
    }

    private TaskSnapshot publish(TaskEntry entry, TaskSnapshot effective) {
        if (entry.publish(effective)) {
            entry.notifyIfNewer(this::fireStatus);
            changes.fireOnChange();
        }
        return entry.snapshot();
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
     * polls {@code get} as described for {@link #cancelAndAwait}. Blocks, so never call it on an
     * event loop.
     *
     * @param ctx the caller, passed to the connector
     * @param request the result request
     * @return the terminal snapshot
     * @throws TaskNotFoundException if the connector does not know the task or hides it from {@code ctx}
     * @throws TaskAwaitException if the task's {@code ttl} elapsed, or
     *     {@link TaskEngineSettings#resultMaxWait()} passed, before it became terminal
     * @throws InterruptedException if interrupted while waiting between polls
     * @throws Exception if the connector fails
     */
    @LegacyApi
    public TaskSnapshot awaitResult(InteractionContext ctx, TaskAwaitResultRequest request) throws Exception {
        var awaitHook = settings.connector().awaitResult();
        if (awaitHook != null) {
            return publish(awaitHook.apply(ctx, request));
        }
        return pollUntilTerminal(ctx, getRequest(request.taskId(), request.meta()));
    }

    /**
     * Cancels a task and blocks until it is {@code cancelled}, for the legacy synchronous cancel.
     * The connector's cancel may settle later; this polls {@code get}, publishing every snapshot and
     * sleeping for its {@code pollInterval}, or {@link TaskEngineSettings#resultPollInterval()} when
     * it suggests none, bounded by the task's {@code ttl} and {@link TaskEngineSettings#resultMaxWait()}.
     * Blocks, so never call it on an event loop.
     *
     * @param ctx the caller, passed to the connector
     * @param request the cancel request
     * @return the {@code cancelled} snapshot
     * @throws TaskNotFoundException if the connector does not know the task or hides it from {@code ctx}
     * @throws TaskAwaitException {@link TaskAwaitException.Reason#TERMINAL} if the task was already
     *     terminal, when the connector's cancel is not called, or settled in another terminal status;
     *     otherwise as for {@link #awaitResult}
     * @throws InterruptedException if interrupted while waiting between polls
     * @throws Exception if the connector fails
     */
    @LegacyApi
    public TaskSnapshot cancelAndAwait(InteractionContext ctx, TaskCancelRequest request) throws Exception {
        var connector = settings.connector();
        var getRequest = getRequest(request.taskId(), request.meta());
        var current = publish(connector.get().apply(ctx, getRequest));
        if (current.status().isTerminal()) {
            throw TaskAwaitException.terminal(current.status());
        }
        connector.cancel().apply(ctx, request);
        var settled = pollUntilTerminal(ctx, getRequest);
        if (settled.status() != TaskState.CANCELLED) {
            logger.debug("Cancel of task {} lost to terminal status {}", request.taskId(), settled.status());
            throw TaskAwaitException.terminal(settled.status());
        }
        return settled;
    }

    private TaskSnapshot pollUntilTerminal(InteractionContext ctx, TaskGetRequest getRequest) throws Exception {
        var get = settings.connector().get();
        var giveUpAt = clock.instant().plus(settings.resultMaxWait());
        while (true) {
            var snapshot = publish(get.apply(ctx, getRequest));
            if (snapshot.status().isTerminal()) {
                return snapshot;
            }
            var now = clock.instant();
            var expiresAt = snapshot.ttl() != null ? snapshot.createdAt().plus(snapshot.ttl()) : null;
            if (expiresAt != null && !now.isBefore(expiresAt)) {
                throw TaskAwaitException.expired(getRequest.taskId());
            }
            if (!now.isBefore(giveUpAt)) {
                throw TaskAwaitException.timedOut(settings.resultMaxWait());
            }
            var deadline = expiresAt != null && expiresAt.isBefore(giveUpAt) ? expiresAt : giveUpAt;
            var pollInterval =
                    snapshot.pollInterval() != null ? snapshot.pollInterval() : settings.resultPollInterval();
            var untilDeadline = Duration.between(now, deadline);
            Thread.sleep(pollInterval.compareTo(untilDeadline) < 0 ? pollInterval : untilDeadline);
        }
    }

    private static TaskGetRequest getRequest(String taskId, @Nullable Map<String, Object> meta) {
        return TaskGetRequest.builder().taskId(taskId).meta(meta).build();
    }

    /**
     * Returns the ids of {@code taskIds} that {@code ctx} may read, as the connector's {@code get}
     * decides. Fails closed: an id the connector refuses or fails on is left out. Caches nothing.
     * Blocks on the connector, so never call it on an event loop.
     *
     * @param ctx the caller
     * @param taskIds the requested task ids
     * @return the readable subset
     */
    public Set<String> readableTaskIds(InteractionContext ctx, Set<String> taskIds) {
        var connector = settings.connector();
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
     * Returns one page of cached projections, ordered by task id.
     *
     * @param limit the page size, or non-positive for the default
     * @param cursor the cursor from the previous page, or {@code null}
     * @return the page
     */
    public PaginatedResult<TaskSnapshot> listCached(int limit, @Nullable String cursor) {
        var effectiveLimit = resolvePageLimit(limit);
        var snapshots = entries.values().stream()
                .map(TaskEntry::snapshot)
                .sorted(Comparator.comparing(TaskSnapshot::taskId))
                .toList();
        return Pagination.paginate(snapshots, effectiveLimit, cursor, TaskSnapshot::taskId);
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
        if (removed) {
            changes.fireOnChange();
        }
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
        var changed = false;
        for (var cached : entries.entrySet()) {
            var taskId = cached.getKey();
            var entry = cached.getValue();
            changed |= entry.evictIfExpired(() -> entries.remove(taskId, entry));
        }
        if (changed) {
            changes.fireOnChange();
        }
    }
}
