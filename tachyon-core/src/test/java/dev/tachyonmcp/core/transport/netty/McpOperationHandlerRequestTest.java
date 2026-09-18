/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty;

import static dev.tachyonmcp.core.test.TestUtils.newEngine;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.json.JsonSchema;
import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.features.tools.ToolDescriptor;
import dev.tachyonmcp.api.server.features.tools.ToolHandler;
import dev.tachyonmcp.api.server.features.tools.ToolRequest;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.McpDispatcher;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * Covers {@link McpOperationHandler}'s POST request-dispatch paths and connection-lifecycle
 * callbacks that the basic handler test does not exercise: JSON-RPC request dispatch, the
 * client→server response/error paths, the POST→SSE upgrade, idle/exception handling, and the
 * stateless GET branch. {@link InteractionHandler} sits in front so {@code handlePostRequest} can
 * resolve the per-channel interaction context, mirroring the production pipeline.
 */
class McpOperationHandlerRequestTest {

    private static final ToolDescriptor PROGRESS_DESCRIPTOR = ToolDescriptor.builder()
            .name("progress_tool")
            .description("emits progress notifications")
            .inputSchema(JsonSchema.objectSchema())
            .build();

    /**
     * Emits two progress notifications (which upgrade the POST response to SSE), then returns a result.
     */
    private static final ToolHandler PROGRESS_TOOL = new ToolHandler() {
        @Override
        public ToolDescriptor descriptor() {
            return PROGRESS_DESCRIPTOR;
        }

        @Override
        public CompletionStage<? extends ToolResult> handleAsync(InteractionContext ctx, ToolRequest request) {
            var pt = request.progressToken();
            ctx.notifications().progress(pt, 0, 100, "Starting");
            ctx.notifications().progress(pt, 100, 100, "Complete");
            return CompletableFuture.completedStage(ToolResult.text("ok"));
        }
    };

    private ServerEngine server;
    private EmbeddedChannel channel;

    @BeforeEach
    void setUp() {
        server = newEngine(
                b -> b.session(s -> s.enabled()),
                s -> s.tools().registerAsync(PROGRESS_TOOL.descriptor(), PROGRESS_TOOL::handleAsync));
        var dispatcher = new McpDispatcher(server, Runnable::run);
        channel = new EmbeddedChannel(
                new InteractionHandler(), new McpOperationHandler(server, dispatcher, Runnable::run));
        server.createSession("sess-op").activate();
    }

    @AfterEach
    void tearDown() {
        channel.close();
        server.close();
    }

    // A successful JSON-RPC request returns its result as a single JSON response (keep-alive path).
    @Test
    void postRequestReturnsJsonResult() {
        var response = post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}");
        assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
        assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE)).contains("application/json");
        assertThat(response.content().toString(StandardCharsets.UTF_8)).contains("result");
        response.release();
    }

    // An unknown method dispatches to a JSON-RPC error envelope, still over a 200 JSON response.
    @Test
    void postUnknownMethodReturnsJsonRpcError() {
        var response = post("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"does/notExist\"}");
        assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
        var body = response.content().toString(StandardCharsets.UTF_8);
        assertThat(body).contains("error").contains("Method not found");
        response.release();
    }

    // A client→server JSON-RPC response (no pending request) is acknowledged with 202.
    @Test
    void postResponseMessageReturnsAccepted() {
        var response = post("{\"jsonrpc\":\"2.0\",\"id\":\"42\",\"result\":{}}");
        assertThat(response.status()).isEqualTo(HttpResponseStatus.ACCEPTED);
        response.release();
    }

    // A client→server JSON-RPC error (no pending request) is acknowledged with 202.
    @Test
    void postErrorMessageReturnsAccepted() {
        var response = post("{\"jsonrpc\":\"2.0\",\"id\":\"42\",\"error\":{\"code\":-1,\"message\":\"boom\"}}");
        assertThat(response.status()).isEqualTo(HttpResponseStatus.ACCEPTED);
        response.release();
    }

    // A non-"initialized" notification is acknowledged with 202 and dispatched asynchronously.
    @Test
    void postCancelledNotificationReturnsAccepted() {
        var response = post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\","
                + "\"params\":{\"requestId\":\"1\",\"reason\":\"user\"}}");
        assertThat(response.status()).isEqualTo(HttpResponseStatus.ACCEPTED);
        response.release();
    }

    // A tool that emits progress upgrades the POST response to an SSE stream; the final result is
    // delivered as the last SSE event (SEP-1699 POST-SSE streaming).
    @Test
    void toolProgressUpgradesPostResponseToSseStream() {
        writeInbound("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"progress_tool\",\"arguments\":{},\"_meta\":{\"progressToken\":42}}}");

        var outbound = drainOutbound();
        try {
            var head = outbound.getFirst();
            assertThat(head).isInstanceOf(HttpResponse.class).isNotInstanceOf(FullHttpResponse.class);
            var sseResponse = (HttpResponse) head;
            assertThat(sseResponse.status()).isEqualTo(HttpResponseStatus.OK);
            assertThat(sseResponse.headers().get(HttpHeaderNames.CONTENT_TYPE)).isEqualTo("text/event-stream");

            var streamBody = contentText(outbound);
            assertThat(streamBody)
                    .contains("notifications/progress")
                    .contains("\"progress\":0.0")
                    .contains("\"progress\":100.0")
                    .contains("result")
                    .contains("ok");
        } finally {
            outbound.forEach(ReferenceCountUtil::release);
        }
    }

    // Once a tool upgrades the POST response to SSE, the stream is long-lived: an idle tick is a
    // no-op — the scheduler drives heartbeats. The channel stays open but no heartbeat is emitted.
    @Test
    void idleOnUpgradedPostSseStreamIsNoOp() throws InterruptedException {
        var descriptor = ToolDescriptor.builder()
                .name("stalled_tool")
                .description("upgrades to SSE then never completes")
                .inputSchema(JsonSchema.objectSchema())
                .build();
        var upgraded = new CountDownLatch(1);
        var neverComplete = new CompletableFuture<ToolResult>();
        ToolHandler stalledTool = new ToolHandler() {

            @Override
            public ToolDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public CompletionStage<ToolResult> handleAsync(InteractionContext ctx, ToolRequest request) {
                ctx.notifications().progress(request.progressToken(), 0, 100, "working");
                upgraded.countDown();
                return neverComplete;
            }
        };
        var srv = newEngine(b -> {}, s -> s.tools().registerAsync(stalledTool.descriptor(), stalledTool::handleAsync));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        var ch = new EmbeddedChannel(
                new InteractionHandler(), new McpOperationHandler(srv, new McpDispatcher(srv, pool), Runnable::run));
        srv.createSession("sess-stall").activate();
        try {
            var request = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1,
                    HttpMethod.POST,
                    "/mcp",
                    Unpooled.copiedBuffer(
                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{"
                                    + "\"name\":\"stalled_tool\",\"arguments\":{},\"_meta\":{\"progressToken\":7}}}",
                            StandardCharsets.UTF_8));
            request.headers()
                    .set(HttpHeaderNames.ORIGIN, "http://localhost:3000")
                    .set("MCP-Session-Id", "sess-stall")
                    .set(HttpHeaderNames.ACCEPT, "application/json, text/event-stream");
            ch.writeInbound(request);
            assertThat(upgraded.await(5, TimeUnit.SECONDS))
                    .as("tool must emit progress within 5 s")
                    .isTrue();
            drain(ch).forEach(ReferenceCountUtil::release);

            ch.pipeline().fireUserEventTriggered(IdleStateEvent.FIRST_READER_IDLE_STATE_EVENT);
            ch.runPendingTasks();

            assertThat(ch.isOpen())
                    .as("upgraded POST-SSE stream must survive an idle tick")
                    .isTrue();
            assertThat((Object) ch.readOutbound())
                    .as("idle tick must NOT emit a heartbeat (scheduler-driven)")
                    .isNull();
        } finally {
            neverComplete.cancel(true);
            ch.close();
            srv.close();
            pool.shutdownNow();
        }
    }

    // GET with a session id that does not exist is rejected with 404 (MCP spec: unknown/terminated
    // session -> 404 Not Found).
    @Test
    void getUnknownSessionReturnsNotFound() {
        var request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/mcp");
        request.headers()
                .set(HttpHeaderNames.ORIGIN, "http://localhost:3000")
                .set(HttpHeaderNames.ACCEPT, "text/event-stream")
                .set("MCP-Session-Id", "ghost");
        channel.writeInbound(request);

        var response = readResponse();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.NOT_FOUND);
        assertThat(response.content().toString(StandardCharsets.UTF_8)).contains("Unknown session");
        response.release();
    }

    // An idle-timeout event closes the channel.
    @Test
    void idleTimeoutClosesChannel() {
        channel.pipeline().fireUserEventTriggered(IdleStateEvent.FIRST_READER_IDLE_STATE_EVENT);
        channel.runPendingTasks();
        assertThat(channel.isOpen()).isFalse();
    }

    // A connection reset (SocketException) closes the channel quietly.
    @Test
    void socketExceptionClosesChannel() {
        channel.pipeline().fireExceptionCaught(new SocketException("connection reset"));
        channel.runPendingTasks();
        assertThat(channel.isOpen()).isFalse();
    }

    // Any other exception closes the channel.
    @Test
    void genericExceptionClosesChannel() {
        channel.pipeline().fireExceptionCaught(new RuntimeException("boom"));
        channel.runPendingTasks();
        assertThat(channel.isOpen()).isFalse();
    }

    // In stateless mode a GET opens an SSE stream without requiring a session.
    @Test
    void statelessGetOpensSseStream() {
        var statelessServer = newEngine(b -> {});
        var ch = new EmbeddedChannel(
                new InteractionHandler(),
                new McpOperationHandler(
                        statelessServer, new McpDispatcher(statelessServer, Runnable::run), Runnable::run));
        try {
            var request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/mcp");
            request.headers()
                    .set(HttpHeaderNames.ORIGIN, "http://localhost:3000")
                    .set(HttpHeaderNames.ACCEPT, "text/event-stream");
            ch.writeInbound(request);

            var outbound = drain(ch);
            try {
                assertThat(outbound.getFirst()).isInstanceOf(HttpResponse.class);
                var response = (HttpResponse) outbound.getFirst();
                assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
                assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE)).isEqualTo("text/event-stream");
            } finally {
                outbound.forEach(ReferenceCountUtil::release);
            }
        } finally {
            ch.close();
            statelessServer.close();
        }
    }

    // A custom SessionIdGenerator sees the request headers even when initialize arrives on a
    // channel already in the operation phase (keep-alive reuse) — not only via the init handler.
    @Test
    void initializeOnOperationChannelUsesCustomSessionIdGenerator() {
        var customServer = newEngine(b -> b.session(s -> s.enabled()
                .sessionIdGenerator(
                        (channelContext, req) -> "tenant-" + req.headers().get("X-Tenant-Id"))));
        var ch = new EmbeddedChannel(
                new InteractionHandler(),
                new McpOperationHandler(customServer, new McpDispatcher(customServer, Runnable::run), Runnable::run));
        try {
            var request = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1,
                    HttpMethod.POST,
                    "/mcp",
                    Unpooled.copiedBuffer(
                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                                    + "\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
                                    + "\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}",
                            StandardCharsets.UTF_8));
            request.headers()
                    .set(HttpHeaderNames.ORIGIN, "http://localhost:3000")
                    .set("X-Tenant-Id", "acme")
                    .set(HttpHeaderNames.ACCEPT, "application/json, text/event-stream");
            ch.writeInbound(request);

            ch.runPendingTasks();
            var msg = ch.readOutbound();
            assertThat(msg).isInstanceOf(FullHttpResponse.class);
            var response = (FullHttpResponse) msg;
            assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
            assertThat(response.headers().get("MCP-Session-Id")).isEqualTo("tenant-acme");
            assertThat(customServer.getSession("tenant-acme")).isPresent();
            response.release();
        } finally {
            ch.close();
            customServer.close();
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Monitoring / slow-request gating
    // ──────────────────────────────────────────────────────────────

    private static final ToolDescriptor SLOW_DESCRIPTOR = ToolDescriptor.builder()
            .name("slow_tool")
            .description("takes 50ms")
            .inputSchema(JsonSchema.objectSchema())
            .build();

    private static final ToolHandler SLOW_TOOL = new ToolHandler() {
        @Override
        public ToolDescriptor descriptor() {
            return SLOW_DESCRIPTOR;
        }

        @Override
        public CompletionStage<ToolResult> handleAsync(InteractionContext ctx, ToolRequest request) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return CompletableFuture.completedStage(ToolResult.text("ok"));
        }
    };

    @Test
    @ResourceLock("SYSTEM_ERR")
    void slowRequestLoggingDefaultDisabledNoWarning() {
        var baos = new ByteArrayOutputStream();
        var origErr = System.err;
        System.setErr(new PrintStream(baos));
        try {
            var srv = newEngine(b -> {}, s -> s.tools().registerAsync(SLOW_TOOL.descriptor(), SLOW_TOOL::handleAsync));
            var ch = new EmbeddedChannel(
                    new InteractionHandler(),
                    new McpOperationHandler(srv, new McpDispatcher(srv, Runnable::run), Runnable::run));
            srv.createSession("sess-slow").activate();
            try {
                var request = new DefaultFullHttpRequest(
                        HttpVersion.HTTP_1_1,
                        HttpMethod.POST,
                        "/mcp",
                        Unpooled.copiedBuffer(
                                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                                        + "\"params\":{\"name\":\"slow_tool\",\"arguments\":{}}}",
                                StandardCharsets.UTF_8));
                request.headers()
                        .set(HttpHeaderNames.ORIGIN, "http://localhost:3000")
                        .set("MCP-Session-Id", "sess-slow");
                ch.writeInbound(request);
                ch.runPendingTasks();
                var msg = ch.readOutbound();
                if (msg instanceof FullHttpResponse resp) {
                    resp.release();
                }
                assertThat(baos.toString()).doesNotContain("Slow POST");
            } finally {
                ch.close();
                srv.close();
            }
        } finally {
            System.setErr(origErr);
        }
    }

    @Test
    @ResourceLock("SYSTEM_ERR")
    void slowRequestLoggingEnabledWithLowThresholdWarns() {
        var baos = new ByteArrayOutputStream();
        var origErr = System.err;
        System.setErr(new PrintStream(baos));
        try {
            var srv = newEngine(
                    b -> b.observability(m -> m.slowRequestLogging().slowRequestThreshold(Duration.ofMillis(1))),
                    s -> s.tools().registerAsync(SLOW_TOOL.descriptor(), SLOW_TOOL::handleAsync));
            var ch = new EmbeddedChannel(
                    new InteractionHandler(),
                    new McpOperationHandler(srv, new McpDispatcher(srv, Runnable::run), Runnable::run));
            srv.createSession("sess-slow2").activate();
            try {
                var request = new DefaultFullHttpRequest(
                        HttpVersion.HTTP_1_1,
                        HttpMethod.POST,
                        "/mcp",
                        Unpooled.copiedBuffer(
                                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                                        + "\"params\":{\"name\":\"slow_tool\",\"arguments\":{}}}",
                                StandardCharsets.UTF_8));
                request.headers()
                        .set(HttpHeaderNames.ORIGIN, "http://localhost:3000")
                        .set("MCP-Session-Id", "sess-slow2");
                ch.writeInbound(request);
                ch.runPendingTasks();
                var msg = ch.readOutbound();
                if (msg instanceof FullHttpResponse resp) {
                    resp.release();
                }
                assertThat(baos.toString()).contains("Slow POST");
            } finally {
                ch.close();
                srv.close();
            }
        } finally {
            System.setErr(origErr);
        }
    }

    private FullHttpResponse post(String body) {
        writeInbound(body);
        return readResponse();
    }

    private void writeInbound(String body) {
        var request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/mcp", Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
        request.headers()
                .set(HttpHeaderNames.ORIGIN, "http://localhost:3000")
                .set("MCP-Session-Id", "sess-op")
                .set(HttpHeaderNames.ACCEPT, "application/json, text/event-stream");
        channel.writeInbound(request);
    }

    private FullHttpResponse readResponse() {
        channel.runPendingTasks();
        var msg = channel.readOutbound();
        assertThat(msg).isInstanceOf(FullHttpResponse.class);
        return (FullHttpResponse) msg;
    }

    private List<Object> drainOutbound() {
        return drain(channel);
    }

    private static List<Object> drain(EmbeddedChannel ch) {
        ch.runPendingTasks();
        var messages = new ArrayList<>();
        Object msg;
        while ((msg = ch.readOutbound()) != null) {
            messages.add(msg);
        }
        assertThat(messages).as("expected at least one outbound message").isNotEmpty();
        return messages;
    }

    private static String contentText(List<Object> messages) {
        var sb = new StringBuilder();
        for (var msg : messages) {
            if (msg instanceof HttpContent content) {
                sb.append(content.content().toString(StandardCharsets.UTF_8));
            }
        }
        return sb.toString();
    }
}
