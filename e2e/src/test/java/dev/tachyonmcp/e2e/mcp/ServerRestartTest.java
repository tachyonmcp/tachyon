/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.testkit.Mcp20251125Client;
import dev.tachyonmcp.testkit.McpTestClients;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@link TachyonServer#stop()} unbinds the transport and keeps everything else, so {@code start()}
 * serves the same registrations and sessions again; {@link TachyonServer#close()} stays terminal.
 */
class ServerRestartTest {

    @Test
    void restartServesSameRegistrationsAndSessionsOnNewTransport() throws Exception {
        try (var server = TachyonServer.builder()
                .network(n -> n.port(0))
                .session(s -> s.enabled())
                .build()) {
            server.tools().register(b -> b.name("before"), (ctx, req) -> ToolResult.text("one"));
            server.start();
            final var firstPort = server.port();
            final String session;
            try (var client = new Mcp20251125Client(firstPort)) {
                session = client.initialize();
                assertThat(callTool(client, session, "before").body()).contains("\"text\":\"one\"");
            }

            server.stop();

            assertThatIllegalStateException().isThrownBy(server::port).withMessageContaining("not started");
            try (var client = new Mcp20251125Client(firstPort)) {
                assertThatThrownBy(() -> client.ping(session, 1)).isInstanceOf(IOException.class);
            }
            server.tools().register(b -> b.name("after"), (ctx, req) -> ToolResult.text("two"));

            server.start();

            try (var client = new Mcp20251125Client(server.port())) {
                assertThat(client.ping(session, 2).statusCode())
                        .as("session survives restart")
                        .isEqualTo(200);
                assertThat(callTool(client, session, "before").body()).contains("\"text\":\"one\"");
                assertThat(callTool(client, session, "after").body()).contains("\"text\":\"two\"");
            }
        }
    }

    @Test
    void stopDrainsInFlightRequestBeforeUnbinding() throws Exception {
        final var result = new CompletableFuture<ToolResult>();
        try (var server = TachyonServer.builder()
                .network(n -> n.port(0))
                .session(s -> s.enabled())
                .runtime(r -> r.shutdownGracePeriod(Duration.ofSeconds(5)))
                .build()) {
            server.tools().registerAsync(b -> b.name("slow"), (ctx, req) -> {
                ctx.notifications().comment("started");
                return result;
            });
            server.start();
            try (var client = new Mcp20251125Client(server.port())) {
                final var session = client.initialize();
                // language=json
                try (var stream = client.openPostStream(session, """
                        {"jsonrpc":"2.0","id":42,"method":"tools/call","params":{"name":"slow","arguments":{}}}
                        """)) {
                    final var stopping = CompletableFuture.runAsync(server::stop, Thread::startVirtualThread);
                    try {
                        await().during(Duration.ofMillis(200))
                                .atMost(Duration.ofSeconds(1))
                                .untilAsserted(() -> assertThat(stopping)
                                        .as("stop waits for the admitted request")
                                        .isNotDone());
                        result.complete(ToolResult.text("done"));
                        final var frame = stream.await(
                                f -> !f.data().isBlank() && f.json().path("id").asInt() == 42, Duration.ofSeconds(3));
                        assertThat(frame.data()).contains("\"text\":\"done\"");
                    } finally {
                        result.complete(ToolResult.empty());
                        stopping.get(10, TimeUnit.SECONDS);
                    }
                }
            }

            server.start();

            try (var client = new Mcp20251125Client(server.port())) {
                final var session = client.initialize();
                assertThat(client.ping(session, 1).statusCode())
                        .as("drain gate reopens after restart")
                        .isEqualTo(200);
            }
        }
    }

    /** A request that never finishes cannot hold stop(): past the grace period it is dropped. */
    @Test
    void stopDropsUnfinishedRequestAfterGracePeriodAndRestarts() throws Exception {
        try (var server = TachyonServer.builder()
                .network(n -> n.port(0))
                .session(s -> s.enabled())
                .runtime(r -> r.shutdownGracePeriod(Duration.ofMillis(300)))
                .build()) {
            server.tools().registerAsync(b -> b.name("stuck"), (ctx, req) -> {
                ctx.notifications().comment("started");
                return new CompletableFuture<>();
            });
            server.start();
            try (var client = new Mcp20251125Client(server.port())) {
                final var session = client.initialize();
                // language=json
                try (var stream = client.openPostStream(session, """
                        {"jsonrpc":"2.0","id":42,"method":"tools/call","params":{"name":"stuck","arguments":{}}}
                        """)) {
                    final var start = System.nanoTime();
                    server.stop();

                    assertThat(Duration.ofNanos(System.nanoTime() - start))
                            .as("stop waits out the grace period, not the request")
                            .isBetween(Duration.ofMillis(250), Duration.ofSeconds(3));
                    stream.assertNoneArrived(
                            f -> !f.data().isBlank() && f.json().path("id").asInt() == 42, Duration.ofMillis(200));
                }
            }

            server.start();

            try (var client = new Mcp20251125Client(server.port())) {
                final var session = client.initialize();
                assertThat(client.ping(session, 1).statusCode())
                        .as("server serves again after a stop that dropped a request")
                        .isEqualTo(200);
            }
        }
    }

    static Stream<Named<Consumer<TachyonServer>>> shutdowns() {
        return Stream.of(Named.of("stop", TachyonServer::stop), Named.of("close", TachyonServer::close));
    }

    /** A listen response pends for the stream's life: shutdown completes it instead of waiting out the grace. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("shutdowns")
    void shutdownEndsListenStreamGracefullyWithoutWaitingOutTheGracePeriod(Consumer<TachyonServer> shutdown)
            throws Exception {
        try (var server = TachyonServer.builder()
                .network(n -> n.port(0))
                .runtime(r -> r.shutdownGracePeriod(Duration.ofSeconds(5)))
                .build()) {
            server.start();
            try (var client = McpTestClients.latest(server.port())) {
                // language=json
                try (var stream = client.openPostStream(null, """
                        {"jsonrpc":"2.0","id":1,"method":"subscriptions/listen",
                          "params":{"notifications":{"toolsListChanged":true}}}
                        """)) {
                    stream.await(
                            f -> f.data().contains("notifications/subscriptions/acknowledged"), Duration.ofSeconds(5));

                    final var start = System.nanoTime();
                    shutdown.accept(server);

                    assertThat(Duration.ofNanos(System.nanoTime() - start))
                            .as("shutdown must not wait out the grace period for an open listen stream")
                            .isLessThan(Duration.ofSeconds(2));
                    final var last = stream.await(
                            f -> !f.data().isBlank() && f.json().path("id").asInt() == 1, Duration.ofSeconds(3));
                    assertThat(last.json().path("result").path("resultType").asString())
                            .as("listen ends with the graceful response, flushed before the transport closed")
                            .isEqualTo("complete");
                }
            }
        }
    }

    @Test
    void stopIsNoOpOutsideRunningStateAndCloseStaysTerminal() {
        final var server = TachyonServer.builder().network(n -> n.port(0)).build();

        assertThatCode(server::stop).as("stop before start").doesNotThrowAnyException();
        server.start();
        server.stop();
        assertThatCode(server::stop).as("second stop").doesNotThrowAnyException();

        server.start();
        server.close();

        assertThatIllegalStateException()
                .as("closed server exposes no stale port")
                .isThrownBy(server::port)
                .withMessageContaining("not started");
        assertThatCode(server::stop).as("stop after close").doesNotThrowAnyException();
        assertThatIllegalStateException().isThrownBy(server::start).withMessage("Server is closed");
    }

    private static HttpResponse<String> callTool(Mcp20251125Client client, String session, String name)
            throws Exception {
        return client.sendRpc(session, """
                {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"%s","arguments":{}}}
                """.formatted(name));
    }
}
