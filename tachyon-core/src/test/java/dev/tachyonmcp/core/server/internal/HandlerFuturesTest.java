/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class HandlerFuturesTest {

    @Test
    void shouldRequireVirtualThread() throws Exception {
        assertThatThrownBy(HandlerFutures::assumeVirtualThread)
                .isInstanceOf(AssertionError.class)
                .hasMessage("Handler MUST run on virtual thread");

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var thread = executor.submit(() -> {
                        HandlerFutures.assumeVirtualThread();
                        return Thread.currentThread();
                    })
                    .get();

            assertThat(thread.isVirtual()).isTrue();
        }
    }

    @Test
    void shouldReturnResultForAlreadyCompleteStage() throws Exception {
        var future = CompletableFuture.completedFuture("done");
        assertThat(HandlerFutures.joinInterruptible(future)).isEqualTo("done");
    }

    @Test
    void shouldUnwrapRuntimeException() {
        var cause = new IllegalArgumentException("boom");
        var future = CompletableFuture.failedFuture(cause);
        assertThatThrownBy(() -> HandlerFutures.joinInterruptible(future))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("boom");
    }

    @Test
    void shouldWrapCheckedExceptionInCompletionException() {
        var cause = new Exception("checked");
        var future = CompletableFuture.failedFuture(cause);
        assertThatThrownBy(() -> HandlerFutures.joinInterruptible(future))
                .isInstanceOf(CompletionException.class)
                .hasCause(cause);
    }

    @Test
    void shouldThrowInterruptedExceptionAndRestoreInterruptFlag() throws Exception {
        var exRef = new AtomicReference<@Nullable Exception>();
        var started = new CountDownLatch(1);
        var blocked = new CountDownLatch(1);

        var blockingFuture = new CompletableFuture<String>() {
            @Override
            public String get() throws InterruptedException, java.util.concurrent.ExecutionException {
                blocked.countDown();
                return super.get();
            }
        };

        var t = new Thread(() -> {
            started.countDown();
            try {
                HandlerFutures.joinInterruptible(blockingFuture);
            } catch (Exception e) {
                exRef.set(e);
            }
        });
        t.start();
        started.await(5, TimeUnit.SECONDS);
        blocked.await(5, TimeUnit.SECONDS);
        t.interrupt();
        t.join(5000);

        assertThat(exRef.get()).isInstanceOf(InterruptedException.class);
        assertThat(t.isInterrupted()).isTrue();
    }
}
