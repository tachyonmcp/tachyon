/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server;

import static dev.tachyonmcp.core.test.TestUtils.newEngine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import dev.tachyonmcp.api.server.config.RuntimeConfig;
import dev.tachyonmcp.api.server.domain.RequestId;
import dev.tachyonmcp.api.server.extensions.AdvertiseMode;
import dev.tachyonmcp.api.server.extensions.ServerExtension;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Verifies that {@link TachyonServer#close()} drains in-flight handlers for the configured
 * {@code shutdownGracePeriod} before force-interrupting them.
 *
 * @author Konstantin Pavlov
 */
@Execution(ExecutionMode.SAME_THREAD)
class ServerShutdownGraceTest {

    @Test
    void defaultGracePeriodIsFiveSeconds() {
        assertThat(RuntimeConfig.builder().build().shutdownGracePeriod()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void closeIsImmediateWhenIdle() {
        var server = TachyonServer.builder().build();

        long start = System.nanoTime();
        server.close();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertThat(elapsedMs)
                .as("idle close must not wait for the grace period")
                .isLessThan(1_000L);
    }

    @Test
    void closeIsIdempotentAndBlocksLaterStart() {
        var server = TachyonServer.builder().build();

        server.close();

        assertThatCode(server::close).as("second close must be a no-op").doesNotThrowAnyException();
        assertThatThrownBy(server::start)
                .as("start after close must not bind a transport on a shut-down engine")
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Server is closed");
    }

    @Test
    void closeDrainsAsyncHandlerAndItsExecutorContinuation() throws Exception {
        final var started = new CountDownLatch(1);
        final var result = new CompletableFuture<ToolResult>();
        final var extensionClosed = new CountDownLatch(1);
        final var extension = new ServerExtension() {
            @Override
            public String extensionId() {
                return "test/shutdown";
            }

            @Override
            public AdvertiseMode advertiseMode() {
                return AdvertiseMode.ALWAYS;
            }

            @Override
            public void shutdown() {
                extensionClosed.countDown();
            }
        };
        final var server = newEngine(
                b -> b.withExtensions(extension).runtime(r -> r.shutdownGracePeriod(Duration.ofSeconds(2))),
                s -> s.tools().registerAsync(builder -> builder.name("async_probe"), (context, request) -> {
                    started.countDown();
                    return result;
                }));
        server.createSession("sess-async").activate();
        final var dispatcher = new McpDispatcher(server, server.executor());
        final var response = dispatcher.dispatchRequestAsync(
                RequestId.of(1), "tools/call", Map.of("name", "async_probe", "arguments", Map.of()), "sess-async");
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        final var closing = CompletableFuture.runAsync(server::close, Thread::startVirtualThread);
        try {
            await().atMost(Duration.ofSeconds(1))
                    .until(() -> dispatcher
                            .dispatchRequestAsync(RequestId.of(2), "ping", Map.of(), "sess-async")
                            .isCompletedExceptionally());
            assertThat(closing).isNotDone();
            assertThat(extensionClosed.getCount()).isEqualTo(1);
            result.complete(ToolResult.empty());
            assertThat(response.get(5, TimeUnit.SECONDS))
                    .isInstanceOfSatisfying(
                            McpDispatcher.DispatchResult.Response.class,
                            value -> assertThat(value.responseBodyString()).doesNotContain("error"));
            closing.get(5, TimeUnit.SECONDS);
            assertThat(extensionClosed.getCount()).isZero();
        } finally {
            result.complete(ToolResult.empty());
            closing.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void closeBoundsUnfinishedAsyncHandlerAndRejectsNewRequests() throws Exception {
        final var started = new CountDownLatch(1);
        final var result = new CompletableFuture<ToolResult>();
        final var server = newEngine(
                b -> b.runtime(r -> r.shutdownGracePeriod(Duration.ofMillis(200))),
                s -> s.tools().registerAsync(builder -> builder.name("async_probe"), (context, request) -> {
                    started.countDown();
                    return result;
                }));
        server.createSession("sess-async").activate();
        final var dispatcher = new McpDispatcher(server, server.executor());
        dispatcher.dispatchRequestAsync(
                RequestId.of(1), "tools/call", Map.of("name", "async_probe", "arguments", Map.of()), "sess-async");
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        final var start = System.nanoTime();
        try {
            server.close();
            assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis()).isBetween(200L, 2000L);
            assertThat(dispatcher.dispatchRequestAsync(RequestId.of(2), "ping", Map.of(), "sess-async"))
                    .isCompletedExceptionally();
        } finally {
            result.complete(ToolResult.empty());
        }
    }

    @Test
    void closeWaitsConfiguredGraceThenInterruptsInFlightHandler() throws Exception {
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);

        ServerEngine server = newEngine(
                b -> b.runtime(r -> r.shutdownGracePeriod(Duration.ofMillis(400))),
                s -> s.tools().register(builder -> builder.name("slow_probe"), (context, request) -> {
                    started.countDown();
                    try {
                        new CountDownLatch(1).await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        interrupted.countDown();
                        Thread.currentThread().interrupt();
                    }
                    return ToolResult.empty();
                }));

        server.createSession("sess-slow").activate();
        var dispatcher = new McpDispatcher(server, server.executor());
        dispatcher.dispatchRequestAsync(
                RequestId.of(1), "tools/call", Map.of("name", "slow_probe", "arguments", Map.of()), "sess-slow");

        assertThat(started.await(5, TimeUnit.SECONDS))
                .as("handler must be running before close")
                .isTrue();

        long start = System.nanoTime();
        server.close();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertThat(elapsedMs)
                .as("close must wait the configured grace, not the old 10s")
                .isBetween(300L, 3_000L);
        assertThat(interrupted.await(5, TimeUnit.SECONDS))
                .as("in-flight handler must be force-interrupted after the grace period")
                .isTrue();
    }
}
