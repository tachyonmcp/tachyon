/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2025_11_25;

import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.server.session.InMemorySessionStore;
import dev.tachyonmcp.core.server.session.SessionKey;
import dev.tachyonmcp.core.server.session.SessionSnapshot;
import dev.tachyonmcp.core.server.session.SessionStore;
import dev.tachyonmcp.testkit.Mcp20251125Client;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A {@link SessionStore} whose {@code terminate} fails or stalls must not keep the local session
 * alive: the session's own SSE stream ends before the store answers. A failed {@code terminate} makes
 * {@code DELETE} answer {@code 500} and close the connection, and the snapshot stays in the store
 * until a retry (or its TTL) removes it.
 */
class SessionTerminationFailureTest {

    private FailingTerminateStore store;
    private TachyonServer server;

    @BeforeEach
    void setUp() {
        store = new FailingTerminateStore();
        server = TachyonServer.builder()
                .session(s -> s.sessionStore(store))
                .network(n -> n.host("localhost").port(0))
                .build();
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void deleteAnswers500ClosesConnectionAndSessionStreamWhenTerminateFails() throws Exception {
        final var engine = (ServerEngine) server;
        try (var client = new Mcp20251125Client(server.port());
                var http = HttpClient.newHttpClient()) {
            final var sessionId = client.initialize();
            final var streamEnd = openSessionStream(http, sessionId);
            assertThat(streamEnd).isNotDone();
            assertThat(engine.getLocalSession(sessionId)).isPresent();
            store.failTerminate.set(true);

            final var response = client.delete(sessionId);

            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(response.body()).isEqualTo("Session termination failed");
            assertThat(response.headers().firstValue("Connection")).hasValue("close");
            assertThat(store.terminateCount.get()).isOne();
            assertThat(streamEnd).succeedsWithin(ofSeconds(5));
            assertThat(engine.getLocalSession(sessionId)).isEmpty();
            assertThat(store.find(sessionId)).isPresent();

            store.failTerminate.set(false);

            assertThat(client.delete(sessionId).statusCode()).isEqualTo(200);
            assertThat(store.find(sessionId)).isEmpty();
            assertThat(client.ping(sessionId, 1).statusCode()).isEqualTo(404);
        }
    }

    @Test
    void deleteEndsSessionStreamBeforeSlowTerminateReturns() throws Exception {
        try (var client = new Mcp20251125Client(server.port());
                var http = HttpClient.newHttpClient();
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var sessionId = client.initialize();
            final var streamEnd = openSessionStream(http, sessionId);
            assertThat(streamEnd).isNotDone();
            store.holdTerminate();
            try {
                final var delete = executor.submit(() -> client.delete(sessionId));
                assertThat(store.terminateEntered.await(5, TimeUnit.SECONDS)).isTrue();

                assertThat(streamEnd)
                        .as("a stalled store must not hold the session's SSE stream open")
                        .succeedsWithin(ofSeconds(5));
                assertThat(delete).isNotDone();

                store.releaseTerminate();

                assertThat(delete)
                        .succeedsWithin(ofSeconds(5))
                        .satisfies(response -> assertThat(response.statusCode()).isEqualTo(200));
            } finally {
                store.releaseTerminate();
            }
            assertThat(store.terminateCount.get()).isOne();
            assertThat(store.find(sessionId)).isEmpty();
            assertThat(client.ping(sessionId, 1).statusCode()).isEqualTo(404);
        }
    }

    private CompletableFuture<byte[]> openSessionStream(HttpClient http, String sessionId) throws Exception {
        final var stream = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + "/mcp"))
                        .header("MCP-Session-Id", sessionId)
                        .header("MCP-Protocol-Version", Mcp20251125Client.PROTOCOL_VERSION)
                        .header("Accept", "text/event-stream")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(stream.statusCode()).isEqualTo(200);
        return CompletableFuture.supplyAsync(() -> {
            try (var body = stream.body()) {
                return body.readAllBytes();
            } catch (IOException e) {
                return new byte[0];
            }
        });
    }

    private static final class FailingTerminateStore implements SessionStore {
        private final InMemorySessionStore delegate = new InMemorySessionStore();
        final AtomicBoolean failTerminate = new AtomicBoolean();
        final AtomicInteger terminateCount = new AtomicInteger();
        final CountDownLatch terminateEntered = new CountDownLatch(1);
        private volatile CountDownLatch terminateGate = new CountDownLatch(0);

        void holdTerminate() {
            terminateGate = new CountDownLatch(1);
        }

        void releaseTerminate() {
            terminateGate.countDown();
        }

        @Override
        public SessionSnapshot create(SessionKey key, Instant expiresAt) {
            return delegate.create(key, expiresAt);
        }

        @Override
        public Optional<SessionSnapshot> find(String sessionId) {
            return delegate.find(sessionId);
        }

        @Override
        public boolean compareAndSet(SessionSnapshot expected, SessionSnapshot updated) {
            return delegate.compareAndSet(expected, updated);
        }

        @Override
        public boolean touch(SessionKey key, Instant expiresAt) {
            return delegate.touch(key, expiresAt);
        }

        @Override
        public boolean terminate(SessionKey key) {
            terminateCount.incrementAndGet();
            terminateEntered.countDown();
            try {
                if (!terminateGate.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("terminate gate not released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            if (failTerminate.get()) {
                throw new IllegalStateException("terminate failed");
            }
            return delegate.terminate(key);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
