/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.features.tasks;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/**
 * Cached task projection plus server-local retention and its push {@link TaskRoute}. The route is
 * set at most once, by the task-augmented tool call that returned the task, and never changes.
 */
@InternalApi
final class TaskEntry {

    private final Duration keepAlive;
    private final Clock clock;
    private volatile TaskSnapshot snapshot;
    private volatile Instant cachedAt;
    private volatile TaskRoute route;
    /** Guards updates and orders notifications; senders must never block while it is held. */
    private final ReentrantLock lock = new ReentrantLock();

    private long notifiedRevision = -1;

    TaskEntry(TaskSnapshot snapshot, TaskRoute route, Duration keepAlive, Clock clock) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.route = Objects.requireNonNull(route, "route");
        this.keepAlive = Objects.requireNonNull(keepAlive, "keepAlive");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.cachedAt = clock.instant();
    }

    /** Applies {@code candidate} if its revision is newer; returns whether it did. */
    boolean publish(TaskSnapshot candidate) {
        lock.lock();
        try {
            if (candidate.revision() <= snapshot.revision()) {
                return false;
            }
            cachedAt = clock.instant();
            snapshot = candidate;
            return true;
        } finally {
            lock.unlock();
        }
    }

    TaskSnapshot snapshot() {
        return snapshot;
    }

    TaskRoute route() {
        return route;
    }

    /**
     * Gives an unrouted entry {@code candidate}; a routed entry keeps its route, so a colliding task
     * id never redirects another caller's push traffic. Revisions sent before the route attaches are
     * not re-sent along it.
     *
     * @return whether the entry took {@code candidate}
     */
    boolean route(TaskRoute candidate) {
        if (candidate.equals(TaskRoute.NONE)) {
            return false;
        }
        lock.lock();
        try {
            if (!route.equals(TaskRoute.NONE)) {
                return false;
            }
            route = candidate;
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Sends the current snapshot along its route unless a revision at least as new was already sent.
     * A publisher that lost the race to a newer one therefore sends nothing instead of a stale
     * status.
     */
    void notifyIfNewer(BiConsumer<TaskSnapshot, TaskRoute> sender) {
        lock.lock();
        try {
            var current = snapshot;
            if (current.revision() <= notifiedRevision) {
                return;
            }
            notifiedRevision = current.revision();
            sender.accept(current, route);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Runs {@code remove} if this entry is expired, checked under the lock that orders publishes: a
     * concurrent publish that renews the task either lands first and keeps it, or lands after.
     *
     * @return whether the entry was expired and {@code remove} reported a removal
     */
    boolean evictIfExpired(BooleanSupplier remove) {
        lock.lock();
        try {
            return isExpired() && remove.getAsBoolean();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Whether the janitor may evict this entry: past the snapshot's {@code ttl} from {@code createdAt}
     * whatever its status, or terminal and cached longer than {@code keepAlive}.
     */
    boolean isExpired() {
        var current = snapshot;
        var now = clock.instant();
        var ttl = current.ttl();
        if (ttl != null && !now.isBefore(current.createdAt().plus(ttl))) {
            return true;
        }
        return current.status().isTerminal()
                && keepAlive.compareTo(Duration.ZERO) > 0
                && !now.isBefore(cachedAt.plus(keepAlive));
    }
}
