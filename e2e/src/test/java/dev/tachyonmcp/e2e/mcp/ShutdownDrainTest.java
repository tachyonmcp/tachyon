/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.tachyonmcp.api.server.domain.RequestId;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.McpDispatcher;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.testkit.Mcp20251125Client;
import io.netty.channel.Channel;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ShutdownDrainTest {
    /** Draining rejects new work with 503, not the 500 a genuine dispatch failure earns. */
    @Test
    void refusesNewRequestsWithServiceUnavailableWhileDraining() throws Exception {
        final var result = new CompletableFuture<ToolResult>();
        final var server = (ServerEngine) TachyonServer.builder()
                .network(n -> n.port(0))
                .session(s -> s.enabled())
                .runtime(r -> r.shutdownGracePeriod(Duration.ofSeconds(5)))
                .build();
        server.tools().registerAsync(b -> b.name("drain"), (context, request) -> {
            context.notifications().comment("started");
            return result;
        });
        server.start();
        try (final var slow = new Mcp20251125Client(server.port());
                final var other = new Mcp20251125Client(server.port())) {
            final var slowSession = slow.initialize();
            final var otherSession = other.initialize();
            // language=json
            try (final var stream = slow.openPostStream(slowSession, """
                    {"jsonrpc":"2.0","id":42,"method":"tools/call","params":{"name":"drain","arguments":{}}}
                    """)) {
                final var closing = CompletableFuture.runAsync(server::close, Thread::startVirtualThread);
                try {
                    await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                        final var refused = other.ping(otherSession, 7);
                        assertThat(refused.statusCode()).isEqualTo(503);
                        assertThat(refused.body()).isEqualTo("Server shutting down");
                    });
                    // The already-admitted request still gets its real answer.
                    result.complete(ToolResult.empty());
                    final var frame = stream.await(
                            f -> !f.data().isBlank() && f.json().path("id").asInt() == 42, Duration.ofSeconds(3));
                    // language=json
                    assertThatJson(frame.data()).isEqualTo("""
                            {"id":42,"jsonrpc":"2.0","result":{"content":[]}}
                            """);
                } finally {
                    result.complete(ToolResult.empty());
                    closing.get(10, TimeUnit.SECONDS);
                }
            }
        }
    }

    @Test
    void drainsPostSseFinalizationAcrossEventLoopHop() throws Exception {
        final var channel = new AtomicReference<Channel>();
        final var result = new CompletableFuture<ToolResult>();
        final var releaseEventLoop = new CountDownLatch(1);
        final var eventLoopBlocked = new CountDownLatch(1);
        final var server = (ServerEngine) TachyonServer.builder()
                .network(n -> n.port(0))
                .session(s -> s.enabled())
                .runtime(r -> r.shutdownGracePeriod(Duration.ofSeconds(5)))
                .pipelineCustomizer(p -> channel.set(p.channel()))
                .build();
        server.tools().registerAsync(b -> b.name("drain"), (context, request) -> {
            context.notifications().comment("started");
            return result;
        });
        server.start();
        var closeStarted = false;
        try (final var client = new Mcp20251125Client(server.port())) {
            final var session = client.initialize();
            // language=json
            try (final var stream = client.openPostStream(session, """
                    {"jsonrpc":"2.0","id":42,"method":"tools/call","params":{"name":"drain","arguments":{}}}
                    """)) {
                channel.get().eventLoop().execute(() -> {
                    eventLoopBlocked.countDown();
                    try {
                        releaseEventLoop.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                assertThat(eventLoopBlocked.await(5, TimeUnit.SECONDS)).isTrue();
                final var closing = CompletableFuture.runAsync(server::close, Thread::startVirtualThread);
                closeStarted = true;
                try {
                    final var dispatcher = new McpDispatcher(server, server.executor());
                    await().atMost(Duration.ofSeconds(2))
                            .until(() -> dispatcher
                                    .dispatchRequestAsync(RequestId.of(99), "ping", Map.of(), session)
                                    .isCompletedExceptionally());
                    result.complete(ToolResult.empty());
                    // The handler is done, but its response still needs the blocked event loop —
                    // close() stays parked until that hop can run.
                    await().during(Duration.ofMillis(200))
                            .atMost(Duration.ofSeconds(1))
                            .untilAsserted(() -> assertThat(closing).isNotDone());
                    releaseEventLoop.countDown();
                    final var frame = stream.await(
                            f -> !f.data().isBlank() && f.json().path("id").asInt() == 42, Duration.ofSeconds(3));
                    // language=json
                    assertThatJson(frame.data()).isEqualTo("""
                            {"id":42,"jsonrpc":"2.0","result":{"content":[]}}
                            """);
                    closing.get(5, TimeUnit.SECONDS);
                } finally {
                    releaseEventLoop.countDown();
                    result.complete(ToolResult.empty());
                    closing.get(10, TimeUnit.SECONDS);
                }
            }
        } finally {
            releaseEventLoop.countDown();
            result.complete(ToolResult.empty());
            // close() is not re-entrant — only close here if the body never got that far.
            if (!closeStarted) server.close();
        }
    }
}
