/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.tachyonmcp.api.server.domain.RequestId;
import dev.tachyonmcp.core.server.McpDispatcher;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.server.session.InMemorySessionStore;
import dev.tachyonmcp.core.server.session.SessionEvent;
import dev.tachyonmcp.core.server.session.SessionKey;
import dev.tachyonmcp.core.server.session.SessionSnapshot;
import dev.tachyonmcp.core.server.session.SessionStore;
import dev.tachyonmcp.core.transport.netty.sse.SseHeartbeat;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class McpOperationHandlerTest {

    private ServerEngine server;
    private EmbeddedChannel channel;

    @BeforeEach
    void setUp() {
        server =
                (ServerEngine) TachyonServer.builder().session(s -> s.enabled()).build();
        channel = new EmbeddedChannel(
                new McpOperationHandler(server, new McpDispatcher(server, Runnable::run), Runnable::run));
    }

    @AfterEach
    void tearDown() {
        channel.finishAndReleaseAll();
        server.close();
    }

    @Test
    void plainOptionsListsAllowedMethods() {
        sendOptions("http://localhost:3000");
        var response = readResponse();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.NO_CONTENT);
        assertThat(response.headers().get(HttpHeaderNames.ALLOW)).isEqualTo("GET, POST, DELETE, OPTIONS");
        assertThat(response.headers().contains(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN))
                .as("CorsHandler owns CORS headers")
                .isFalse();
        response.release();

        sendOptions(null);
        var withoutOrigin = readResponse();
        assertThat(withoutOrigin.status())
                .as("a non-browser client needs no Origin")
                .isEqualTo(HttpResponseStatus.NO_CONTENT);
        withoutOrigin.release();
    }

    @Test
    void postNotificationReturns202() {
        server.createSession("sess-notif").activate();

        var body = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";
        var request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/mcp", Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
        request.headers()
                .set(HttpHeaderNames.ORIGIN, "http://localhost:3000")
                .set("MCP-Session-Id", "sess-notif")
                .set(HttpHeaderNames.ACCEPT, "application/json, text/event-stream");
        channel.writeInbound(request);

        var response = readResponse();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.ACCEPTED);
        response.release();
    }

    @Test
    void responseCannotCompleteRequestOwnedByAnotherSession() {
        // MCP 2025-11-25 Streamable HTTP session ownership.
        var owner = server.createSession("sess-response-owner");
        owner.activate();
        server.createSession("sess-response-other").activate();
        var pending = server.sendRequest(owner, "sampling/createMessage", Map.of());
        var requestId = outboundRequestId(owner.id());
        var body = """
                {"jsonrpc":"2.0","id":"%s","result":{"answer":42}}""".formatted(requestId);

        assertAccepted(postJsonRpc("sess-response-other", body));
        assertThat(pending).isNotDone();

        assertAccepted(postJsonRpc(owner.id(), body));
        assertThat(pending).isCompletedWithValue("{\"answer\":42}");
    }

    @Test
    void errorCannotFailRequestOwnedByAnotherSession() {
        // MCP 2025-11-25 Streamable HTTP session ownership.
        var owner = server.createSession("sess-error-owner");
        owner.activate();
        server.createSession("sess-error-other").activate();
        var pending = server.sendRequest(owner, "elicitation/create", Map.of());
        var requestId = outboundRequestId(owner.id());
        var body = """
                {"jsonrpc":"2.0","id":"%s","error":{"code":-32000,"message":"Rejected"}}""".formatted(requestId);

        assertAccepted(postJsonRpc("sess-error-other", body));
        assertThat(pending).isNotDone();

        assertAccepted(postJsonRpc(owner.id(), body));
        assertThat(pending).isCompletedExceptionally();
        assertThat(pending.exceptionNow()).hasMessage("-32000: Rejected");
    }

    @Test
    void postWithUnknownSessionReturns404() {
        var body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
        var request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/mcp", Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
        request.headers()
                .set(HttpHeaderNames.ORIGIN, "http://localhost:3000")
                .set("MCP-Session-Id", "sess-missing")
                .set(HttpHeaderNames.ACCEPT, "application/json, text/event-stream");
        channel.writeInbound(request);

        var response = readResponse();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.NOT_FOUND);
        assertThat(response.content().toString(StandardCharsets.UTF_8)).contains("Unknown session");
        response.release();
    }

    @Test
    void getWithoutSessionReturnsError() {
        var request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/mcp");
        request.headers()
                .set(HttpHeaderNames.ORIGIN, "http://localhost:3000")
                .set(HttpHeaderNames.ACCEPT, "text/event-stream");
        channel.writeInbound(request);

        var response = readResponse();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.BAD_REQUEST);
        assertThat(response.content().toString(StandardCharsets.UTF_8)).contains("Missing MCP-Session-Id");
        response.release();
    }

    @Test
    void getWithValidSessionReturnsSseStream() {
        server.createSession("sess-get").activate();

        var request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/mcp");
        request.headers()
                .set(HttpHeaderNames.ORIGIN, "http://localhost:3000")
                .set(HttpHeaderNames.ACCEPT, "text/event-stream")
                .set("MCP-Session-Id", "sess-get");
        channel.writeInbound(request);

        channel.runPendingTasks();
        var msg = channel.readOutbound();
        assertThat(msg).isInstanceOf(HttpResponse.class);
        var response = (HttpResponse) msg;
        assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
        assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE)).isEqualTo("text/event-stream");
    }

    @Test
    void sessionLookupRunsOutsideEventLoop() throws Exception {
        final var lookupThread = new AtomicReference<@Nullable Thread>();
        final var lookupFinished = new CountDownLatch(1);

        try (final var store = new ThreadRecordingSessionStore(lookupThread, lookupFinished);
                final var testServer = (ServerEngine) TachyonServer.builder()
                        .session(config -> config.enabled().sessionStore(store))
                        .build()) {
            try (final var lookupExecutor = Executors.newSingleThreadExecutor()) {
                final var testChannel = new EmbeddedChannel(new McpOperationHandler(
                        testServer, new McpDispatcher(testServer, Runnable::run), lookupExecutor));
                try {
                    final var eventLoopThread = Thread.currentThread();
                    final var request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/mcp");
                    request.headers().set("MCP-Session-Id", "missing");

                    testChannel.writeInbound(request);

                    assertThat(lookupFinished.await(2, TimeUnit.SECONDS)).isTrue();
                    assertThat(lookupThread)
                            .hasValueSatisfying(thread -> assertThat(thread).isNotSameAs(eventLoopThread));
                    await().pollInSameThread().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
                        testChannel.runPendingTasks();
                        assertThat(testChannel.outboundMessages()).isNotEmpty();
                    });
                    final var response = (FullHttpResponse) testChannel.readOutbound();
                    assertThat(response.status()).isEqualTo(HttpResponseStatus.NOT_FOUND);
                    response.release();
                } finally {
                    testChannel.finishAndReleaseAll();
                }
            }
        }
    }

    @Test
    void postSessionLookupFailureReturns500() {
        final var store = mock(SessionStore.class);
        when(store.find("unavailable")).thenThrow(new IllegalStateException("Store unavailable"));
        final var testServer = (ServerEngine) TachyonServer.builder()
                .session(config -> config.enabled().sessionStore(store))
                .build();
        final var testChannel = new EmbeddedChannel(
                new InteractionHandler(),
                new McpOperationHandler(testServer, new McpDispatcher(testServer, Runnable::run), Runnable::run));
        try {
            final var request = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1, HttpMethod.POST, "/mcp", Unpooled.copiedBuffer("""
                {"jsonrpc":"2.0","id":1,"method":"ping"}
                """, StandardCharsets.UTF_8));
            request.headers().set("MCP-Session-Id", "unavailable");
            testChannel.writeInbound(request);
            testChannel.runPendingTasks();
            final var response = (FullHttpResponse) testChannel.readOutbound();
            assertThat(response).isNotNull();
            try {
                assertThat(response.status()).isEqualTo(HttpResponseStatus.INTERNAL_SERVER_ERROR);
                assertThat(response.content().toString(StandardCharsets.UTF_8)).isEqualTo("Session lookup failed");
                assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE)).startsWith("text/plain");
                assertThat(response.headers().get(HttpHeaderNames.CONNECTION)).isEqualTo("close");
                assertThat(request.refCnt()).isZero();
                verify(store).find("unavailable");
            } finally {
                response.release();
            }
        } finally {
            testChannel.finishAndReleaseAll();
            testServer.close();
        }
    }

    private static final class ThreadRecordingSessionStore implements SessionStore {
        private final InMemorySessionStore delegate = new InMemorySessionStore();
        private final AtomicReference<Thread> lookupThread;
        private final CountDownLatch lookupFinished;

        private ThreadRecordingSessionStore(AtomicReference<Thread> lookupThread, CountDownLatch lookupFinished) {
            this.lookupThread = lookupThread;
            this.lookupFinished = lookupFinished;
        }

        @Override
        public SessionSnapshot create(SessionKey key, Instant expiresAt) {
            return delegate.create(key, expiresAt);
        }

        @Override
        public Optional<SessionSnapshot> find(String sessionId) {
            lookupThread.set(Thread.currentThread());
            lookupFinished.countDown();
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
            return delegate.terminate(key);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    @Test
    void idleOnSseStreamIsNoOp() {
        server.createSession("sess-hb").activate();

        var request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/mcp");
        request.headers()
                .set(HttpHeaderNames.ORIGIN, "http://localhost:3000")
                .set(HttpHeaderNames.ACCEPT, "text/event-stream")
                .set("MCP-Session-Id", "sess-hb");
        channel.writeInbound(request);
        channel.runPendingTasks();
        drainOutbound();

        // SSE channels: idle tick is a no-op — the scheduler drives heartbeats.
        channel.pipeline().fireUserEventTriggered(IdleStateEvent.FIRST_READER_IDLE_STATE_EVENT);
        channel.runPendingTasks();

        assertThat(channel.isOpen()).as("SSE stream must survive an idle tick").isTrue();
        assertThat((Object) channel.readOutbound())
                .as("idle tick must NOT emit a heartbeat (scheduler-driven)")
                .isNull();
    }

    @Test
    void writerIdleOnSseStreamClosesChannel() {
        server.createSession("sess-stall").activate();

        var request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/mcp");
        request.headers()
                .set(HttpHeaderNames.ORIGIN, "http://localhost:3000")
                .set(HttpHeaderNames.ACCEPT, "text/event-stream")
                .set("MCP-Session-Id", "sess-stall");
        channel.writeInbound(request);
        channel.runPendingTasks();
        drainOutbound();
        assertThat(SseHeartbeat.isEnabled(channel)).isTrue();

        channel.pipeline().fireUserEventTriggered(IdleStateEvent.FIRST_WRITER_IDLE_STATE_EVENT);
        channel.runPendingTasks();

        assertThat(channel.isOpen())
                .as("writer idle means heartbeats stalled, so the stream must close")
                .isFalse();
    }

    @Test
    void idleOnNonSseChannelClosesChannel() {
        channel.pipeline().fireUserEventTriggered(IdleStateEvent.FIRST_READER_IDLE_STATE_EVENT);
        channel.runPendingTasks();

        assertThat(channel.isOpen())
                .as("plain idle keep-alive socket must close on idle")
                .isFalse();
    }

    @Test
    void deleteWithoutSessionReturnsError() {
        var request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.DELETE, "/mcp");
        request.headers().set(HttpHeaderNames.ORIGIN, "http://localhost:3000");
        channel.writeInbound(request);

        var response = readResponse();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.BAD_REQUEST);
        response.release();
    }

    @Test
    void deleteWithValidSessionReturnsOk() {
        server.createSession("sess-del").activate();

        var request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.DELETE, "/mcp");
        request.headers().set(HttpHeaderNames.ORIGIN, "http://localhost:3000").set("MCP-Session-Id", "sess-del");
        channel.writeInbound(request);

        var response = readResponse();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
        response.release();
    }

    @Test
    void deleteWithUnknownSessionReturnsNotFound() {
        var request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.DELETE, "/mcp");
        request.headers().set(HttpHeaderNames.ORIGIN, "http://localhost:3000").set("MCP-Session-Id", "ghost");
        channel.writeInbound(request);

        var response = readResponse();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.NOT_FOUND);
        assertThat(response.content().toString(StandardCharsets.UTF_8)).contains("Unknown session");
        response.release();
    }

    @Test
    void postMalformedJsonReturnsParseError() {
        server.createSession("sess-parse").activate();

        var body = "not json";
        var request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/mcp", Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
        request.headers()
                .set(HttpHeaderNames.ORIGIN, "http://localhost:3000")
                .set("MCP-Session-Id", "sess-parse")
                .set(HttpHeaderNames.ACCEPT, "application/json, text/event-stream");
        channel.writeInbound(request);

        var response = readResponse();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.BAD_REQUEST);
        var content = response.content().toString(StandardCharsets.UTF_8);
        assertThat(content).contains("error");
        response.release();
    }

    @Test
    void unsupportedMethodReturns405() {
        var request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.PATCH, "/mcp");
        request.headers().set(HttpHeaderNames.ORIGIN, "http://localhost:3000");
        channel.writeInbound(request);

        var response = readResponse();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.METHOD_NOT_ALLOWED);
        response.release();
    }

    private void sendOptions(@Nullable String origin) {
        var request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.OPTIONS, "/mcp");
        if (origin != null) {
            request.headers().set(HttpHeaderNames.ORIGIN, origin);
        }
        channel.writeInbound(request);
    }

    private FullHttpResponse postJsonRpc(String sessionId, String body) {
        var request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/mcp", Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
        request.headers()
                .set(HttpHeaderNames.ORIGIN, "http://localhost:3000")
                .set("MCP-Session-Id", sessionId)
                .set(HttpHeaderNames.ACCEPT, "application/json, text/event-stream");
        channel.writeInbound(request);
        return readResponse();
    }

    private RequestId outboundRequestId(String sessionId) {
        return server.replay(sessionId, -1).stream()
                .filter(SessionEvent.OutboundRequestEvent.class::isInstance)
                .map(SessionEvent.OutboundRequestEvent.class::cast)
                .map(SessionEvent.OutboundRequestEvent::requestId)
                .findFirst()
                .orElseThrow();
    }

    private static void assertAccepted(FullHttpResponse response) {
        try {
            assertThat(response.status()).isEqualTo(HttpResponseStatus.ACCEPTED);
        } finally {
            response.release();
        }
    }

    private FullHttpResponse readResponse() {
        channel.runPendingTasks();
        var msg = channel.readOutbound();
        assertThat(msg).isInstanceOf(FullHttpResponse.class);
        return (FullHttpResponse) msg;
    }

    private void drainOutbound() {
        Object msg;
        while ((msg = channel.readOutbound()) != null) {
            ReferenceCountUtil.release(msg);
        }
    }
}
