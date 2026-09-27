/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.tasks;

import static java.nio.charset.StandardCharsets.UTF_8;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.tachyonmcp.api.server.features.tasks.TaskConnector;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.core.server.ServerBuilder;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.server.config.SessionConfig;
import dev.tachyonmcp.testkit.Mcp20251125Client;
import dev.tachyonmcp.testkit.McpTestServers;
import java.io.OutputStream;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * What ends a legacy blocking {@code tasks/result} wait. The wait is released once its response can
 * no longer be delivered; the task itself is never touched.
 */
class LegacyResultWaitE2eTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-27T07:00:00Z");
    private static final Duration WITHIN = Duration.ofSeconds(5);
    private static final Consumer<ServerBuilder> STATEFUL = b -> b.session(SessionConfig.Builder::enabled);

    private final AtomicInteger polls = new AtomicInteger();
    private final AtomicInteger cancels = new AtomicInteger();
    private final AtomicReference<@Nullable Thread> waiter = new AtomicReference<>();
    private final TaskConnector connector = TaskConnector.builder()
            .get((ctx, request) -> {
                waiter.set(Thread.currentThread());
                polls.incrementAndGet();
                return TaskSnapshot.builder()
                        .from(TaskSnapshot.working(request.taskId(), CREATED_AT, 1))
                        .pollInterval(Duration.ofMillis(10))
                        .build();
            })
            .cancel((ctx, request) -> cancels.incrementAndGet())
            .update((ctx, request) -> {})
            .build();

    // 2025-11-25 Transports § Sending Messages 6: disconnection SHOULD NOT be interpreted as the
    // client cancelling its request; the stream stays resumable while the session lives. Once the
    // session ends its id answers 404, so the response is undeliverable and the wait ends. Tasks are
    // durable: ending a session never cancels one.
    @Test
    void statefulWaitSurvivesADisconnectAndEndsWithTheSession() throws Exception {
        try (var server = startServer(STATEFUL);
                var client = new Mcp20251125Client(server.port())) {
            var sessionId = client.initialize();

            try (var socket = new Socket("localhost", server.port())) {
                send(socket.getOutputStream(), sessionId);
                await().atMost(WITHIN).until(() -> polls.get() >= 3);
            }
            var handler = awaitWaiter();
            var atDisconnect = polls.get();
            await("disconnect is not a cancel: the wait keeps polling")
                    .atMost(WITHIN)
                    .until(() -> polls.get() >= atDisconnect + 5);
            assertThat(handler.isAlive()).isTrue();

            assertThat(client.delete(sessionId).statusCode()).isEqualTo(200);
            await("session end releases the wait").atMost(WITHIN).until(() -> !handler.isAlive());

            try (var next = new Mcp20251125Client(server.port())) {
                var nextSessionId = next.initialize();
                assertThatJson(next.post(nextSessionId, rpc("tasks/get")).body())
                        .as("the task outlives the session")
                        .inPath("$.result.status")
                        .isEqualTo("working");
            }
            assertThat(cancels).as("the task was never cancelled").hasValue(0);
        }
    }

    // A requestor blocked on an open connection is not idle, so its session outlives the idle ttl.
    // Once it disconnects the session idles out like any other, and that releases the wait.
    @Test
    void openWaitKeepsItsSessionAliveUntilTheRequestorDisconnects() throws Exception {
        var shortSessions = STATEFUL.andThen(b ->
                b.session(s -> s.enabled().sessionTtl(Duration.ofMillis(300)).janitorInterval(Duration.ofMillis(50))));
        try (var server = startServer(shortSessions);
                var client = new Mcp20251125Client(server.port())) {
            var sessionId = client.initialize();

            Thread handler;
            try (var socket = new Socket("localhost", server.port())) {
                send(socket.getOutputStream(), sessionId);
                handler = awaitWaiter();
                var atStart = polls.get();
                await("wait well past the session ttl").atMost(WITHIN).until(() -> polls.get() >= atStart + 100);
                assertThat(handler.isAlive())
                        .as("session not expired under an open wait")
                        .isTrue();
            }

            await("idle session expires after the disconnect, releasing the wait")
                    .atMost(WITHIN)
                    .until(() -> !handler.isAlive());
            assertThat(client.ping(sessionId, 2).statusCode()).isEqualTo(404);
            assertThat(cancels).as("the task was never cancelled").hasValue(0);
        }
    }

    // Stateless servers reject Last-Event-ID, so a dropped connection can never be resumed: the
    // response is undeliverable and the wait ends, still without cancelling the task.
    @Test
    void statelessWaitEndsWithTheConnection() throws Exception {
        try (var server = startServer(ServerBuilder::stateless)) {
            try (var socket = new Socket("localhost", server.port())) {
                send(socket.getOutputStream(), null);
                await().atMost(WITHIN).until(() -> polls.get() >= 3);
            }
            var handler = awaitWaiter();

            await("dropped stateless connection releases the wait")
                    .atMost(WITHIN)
                    .until(() -> !handler.isAlive());
            assertThat(cancels).as("the task was never cancelled").hasValue(0);
        }
    }

    // 2025-11-25 Cancellation: the explicit way to stop waiting. Tasks § Retrieving Task Results:
    // after a cancelled tasks/result the requestor may keep polling with tasks/get.
    @Test
    void cancelledNotificationEndsTheWaitButNotTheTask() throws Exception {
        try (var server = startServer(STATEFUL);
                var client = new Mcp20251125Client(server.port())) {
            var sessionId = client.initialize();

            try (var socket = new Socket("localhost", server.port())) {
                send(socket.getOutputStream(), sessionId);
                await().atMost(WITHIN).until(() -> polls.get() >= 3);
                var handler = awaitWaiter();

                assertThat(client.notify("notifications/cancelled", Map.of("requestId", 7))
                                .statusCode())
                        .isEqualTo(202);
                await("cancelled request releases the wait").atMost(WITHIN).until(() -> !handler.isAlive());
            }
            assertThatJson(client.post(sessionId, rpc("tasks/get")).body())
                    .inPath("$.result.status")
                    .isEqualTo("working");
            assertThat(cancels).as("the task was never cancelled").hasValue(0);
        }
    }

    private Thread awaitWaiter() {
        await().atMost(WITHIN).until(() -> waiter.get() != null);
        return waiter.get();
    }

    private static void send(OutputStream out, @Nullable String sessionId) throws Exception {
        var body = rpc("tasks/result").getBytes(UTF_8);
        var head = "POST /mcp HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Content-Type: application/json\r\n"
                + "Accept: application/json, text/event-stream\r\n"
                + "MCP-Protocol-Version: 2025-11-25\r\n"
                + (sessionId != null ? "MCP-Session-Id: " + sessionId + "\r\n" : "")
                + "Content-Length: " + body.length + "\r\n\r\n";
        out.write(head.getBytes(UTF_8));
        out.write(body);
        out.flush();
    }

    private static String rpc(String method) {
        return "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"" + method + "\",\"params\":{\"taskId\":\"task-1\"}}";
    }

    private TachyonServer startServer(Consumer<ServerBuilder> mode) {
        return McpTestServers.start(
                builder -> {
                    mode.accept(builder);
                    builder.withExtension(TasksExtension.class, t -> t.connector(connector));
                },
                it -> {});
    }
}
