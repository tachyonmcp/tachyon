/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.internal;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Tracks admitted requests until their complete dispatch pipeline terminates.
 *
 * <p>Guarded by a {@link ReentrantLock}, not {@code synchronized}: admission runs on virtual
 * threads for every request, and a VT blocked on a monitor pins its carrier (Java 21; fixed in
 * JEP 491 / Java 24). A j.u.c lock parks the VT instead, and {@link Condition#awaitNanos} lets
 * {@link #drain} wait out the grace period without pinning the thread that called close().
 */
public final class OperationTracker {

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition idle = lock.newCondition();
    private boolean closing;
    private int active;

    /**
     * Admits an operation unless shutdown has started, then tracks dispatch and transport
     * completion under that one admission decision.
     */
    public <T> CompletableFuture<T> execute(
            Supplier<CompletableFuture<T>> operation, CompletableFuture<Void> transportCompletion) {
        lock.lock();
        try {
            if (closing) {
                return CompletableFuture.failedFuture(new RejectedExecutionException("Server is shutting down"));
            }
            active++;
        } finally {
            lock.unlock();
        }
        try {
            final var result = operation.get();
            CompletableFuture.allOf(result, transportCompletion).whenComplete((value, failure) -> finished());
            return result;
        } catch (Throwable failure) {
            finished();
            throw failure;
        }
    }

    private void finished() {
        lock.lock();
        try {
            active--;
            if (closing && active == 0) idle.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Stops admission and waits for active operations using the shared shutdown deadline. */
    public void drain(long deadline) throws InterruptedException {
        lock.lock();
        try {
            closing = true;
            while (active != 0) {
                final var remaining = deadline - System.nanoTime();
                if (remaining <= 0) return;
                idle.awaitNanos(remaining);
            }
        } finally {
            lock.unlock();
        }
    }
}
