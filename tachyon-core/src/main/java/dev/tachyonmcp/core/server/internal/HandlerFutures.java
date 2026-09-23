/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.internal;

import dev.tachyonmcp.api.annotations.InternalApi;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;

/**
 * Static utilities for common CompletionStage patterns.
 */
@InternalApi
public final class HandlerFutures {

    private HandlerFutures() {}

    public static void assumeVirtualThread() {
        assert Thread.currentThread().isVirtual() : "Handler MUST run on virtual thread";
    }

    /**
     * Joins a CompletionStage via blocking {@code .get()}, restoring interrupt and unwrapping
     * ExecutionException. For use on virtual threads where blocking is expected.
     *
     * @param <T>   the result type
     * @param stage the stage to join
     * @return the result of the stage
     * @throws Exception if the stage completed exceptionally
     */
    public static <T> T joinInterruptible(CompletionStage<T> stage) throws Exception {
        try {
            return stage.toCompletableFuture().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (ExecutionException e) {
            var cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new CompletionException(cause);
        }
    }

    /**
     * Unwraps a single level of {@link CompletionException}, returning the original cause when
     * present. Mirrors the unwrap a {@code CompletionStage} callback receives after an async hop.
     *
     * @param throwable the throwable to unwrap
     * @return the original cause, or {@code throwable} itself if not a {@code CompletionException}
     */
    public static Throwable unwrap(Throwable throwable) {
        return throwable instanceof CompletionException ce && ce.getCause() != null ? ce.getCause() : throwable;
    }

    /**
     * Applies {@code fn} to the outcome of {@code stage}. Runs inline on the calling thread when
     * the stage is already complete (the common case: sync handlers, already-resolved async
     * ones) to avoid an unconditional executor hop; otherwise schedules {@code fn} on
     * {@code executor} so a handler completing from a foreign thread doesn't leak that thread
     * into response mapping. Cancelling the mapped future also cancels the source future.
     *
     * @param <T>      the input result type
     * @param <R>      the output result type
     * @param stage    the stage whose outcome to map
     * @param executor the executor for async completion
     * @param fn       the mapping function
     * @return a new stage with the mapped result
     */
    public static <T, R> CompletionStage<R> completeOn(
            CompletionStage<T> stage,
            Executor executor,
            BiFunction<? super @Nullable T, @Nullable Throwable, ? extends R> fn) {
        var future = stage.toCompletableFuture();
        final var mapped = future.isDone() ? future.<R>handle(fn) : future.<R>handleAsync(fn, executor);
        mapped.whenComplete((result, error) -> {
            if (mapped.isCancelled()) future.cancel(true);
        });
        return mapped;
    }

    /**
     * Adapts a synchronous handler body into a {@code CompletionStage}: a completed stage on
     * success, a failed one carrying the thrown exception otherwise. Shared by the sync-handler
     * internal adapters from synchronous feature functions.
     *
     * @param <R>     the result type
     * @param handler the synchronous handler body
     * @return a completed or failed stage
     */
    public static <R> CompletionStage<R> completedOrFailed(Callable<? extends R> handler) {
        try {
            return CompletableFuture.completedFuture(handler.call());
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Invokes a handler, guards against a synchronous throw or a {@code null} returned stage, and
     * maps the outcome via {@link #completeOn}. Shared registry-dispatch skeleton: obtain the
     * handler's stage safely, then compose the result without blocking. The mapper receives the
     * failure cause already {@link #unwrap unwrapped} (or {@code null} on success).
     *
     * @param <R>              the handler result type
     * @param nullStageMessage message for the NPE if invocation returns null
     * @param invocation       the handler invocation
     * @param executor         the executor for async completion
     * @param resultMapper     maps result or failure to the dispatch outcome
     * @return a stage with the mapped result
     */
    public static <R> CompletionStage<Object> invokeAndMap(
            String nullStageMessage,
            Callable<? extends @Nullable CompletionStage<? extends R>> invocation,
            Executor executor,
            BiFunction<? super R, @Nullable Throwable, @Nullable Object> resultMapper) {
        CompletionStage<? extends R> stage;
        try {
            stage = invocation.call();
            if (stage == null) {
                throw new NullPointerException(nullStageMessage);
            }
        } catch (Exception e) {
            stage = CompletableFuture.failedFuture(e);
        }
        return completeOn(stage, executor, (result, ex) -> resultMapper.apply(result, ex == null ? null : unwrap(ex)));
    }
}
