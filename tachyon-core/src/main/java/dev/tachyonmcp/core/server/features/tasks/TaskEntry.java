/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.features.tasks;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.server.domain.ProgressToken;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;

/**
 * Cached task projection plus server-local retention. The owning session and progress token come
 * from the task-augmented tool call that created the task and never change.
 */
@InternalApi
final class TaskEntry {

    private final @Nullable String ownerSessionId;
    private final @Nullable ProgressToken progressToken;
    private final Duration keepAlive;
    private final Clock clock;
    private volatile TaskSnapshot snapshot;
    private volatile Instant cachedAt;
    /** Guards updates and orders notifications; senders must never block while it is held. */
    private final ReentrantLock lock = new ReentrantLock();

    private long notifiedRevision = -1;

    TaskEntry(
            TaskSnapshot snapshot,
            @Nullable String ownerSessionId,
            @Nullable ProgressToken progressToken,
            Duration keepAlive,
            Clock clock) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.ownerSessionId = ownerSessionId;
        this.progressToken = progressToken;
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

    @Nullable
    String ownerSessionId() {
        return ownerSessionId;
    }

    @Nullable
    ProgressToken progressToken() {
        return progressToken;
    }

    /** Whether {@code sessionId} is this task's owner; {@code null} matches an ownerless task. */
    boolean ownedBy(@Nullable String sessionId) {
        return Objects.equals(ownerSessionId, sessionId);
    }

    /**
     * Sends the current snapshot to its owner unless a revision at least as new was already sent.
     * A publisher that lost the race to a newer one therefore sends nothing instead of a stale
     * status.
     */
    void notifyIfNewer(BiConsumer<TaskSnapshot, @Nullable String> sender) {
        lock.lock();
        try {
            var current = snapshot;
            if (current.revision() <= notifiedRevision) {
                return;
            }
            notifiedRevision = current.revision();
            sender.accept(current, ownerSessionId);
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
        if (ttl != null
                && (ownerSessionId == null || current.status().isTerminal())
                && !now.isBefore(current.createdAt().plus(ttl))) {
            return true;
        }
        return current.status().isTerminal()
                && keepAlive.compareTo(Duration.ZERO) > 0
                && !now.isBefore(cachedAt.plus(keepAlive));
    }
}
