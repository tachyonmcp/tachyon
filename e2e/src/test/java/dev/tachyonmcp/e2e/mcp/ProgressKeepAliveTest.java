/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp;

import static dev.tachyonmcp.testkit.JsonRpcResponseAssert.assertThatJsonRpcResponse;
import static dev.tachyonmcp.testkit.McpClient.extractJsonRpcResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.features.tools.AbstractToolHandler;
import dev.tachyonmcp.api.server.features.tools.ToolDescriptor;
import dev.tachyonmcp.api.server.features.tools.ToolRequest;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.transport.netty.McpChannelInitializer;
import dev.tachyonmcp.core.transport.netty.NettyIoEngine;
import dev.tachyonmcp.core.transport.netty.NettyServer;
import dev.tachyonmcp.core.transport.netty.NettyServerConfig;
import dev.tachyonmcp.testkit.Mcp20251125Client;
import dev.tachyonmcp.testkit.Mcp20260728Client;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies the lazy SSE upgrade and keep-alive paths for a long-running POST. Two triggers upgrade
 * the buffered JSON response to {@code text/event-stream} and arm the {@code SseHeartbeat} scheduler:
 *
 * <ul>
 *   <li>token-driven — a tool emits {@code notifications/progress} using the client's
 *       {@code _meta.progressToken} ({@link ProgressHandler});
 *   <li>token-free — a tool emits an empty SSE comment via {@code ctx.notifications().comment(null)}
 *       ({@link CommentHandler}), for when no progress token is available.
 * </ul>
 *
 * <p>Once upgraded, a fixed-rate scheduler (not reader-idle) emits {@code :\r\n} heartbeats at
 * {@code network().heartbeatInterval()} so the stream stays open. {@link #warmUp()} JIT-warms both
 * dispatch paths so the first server→client byte flushes sub-millisecond. The heartbeat interval is
 * kept far below the tool runtime so several heartbeats fire even on a slow CI runner.
 *
 * @author Konstantin Pavlov
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProgressKeepAliveTest {

    private static final Duration READER_IDLE = Duration.ofSeconds(1);

    private static final Duration HEARTBEAT = Duration.ofMillis(250);
    private static final long SLOW_SLEEP_MS = 3_000L;

    // slow-progress request asks for progress: the client supplies _meta.progressToken, which the
    // handler forwards to ctx.notifications().progress(...). This is the token-driven keep-alive.
    private static final String TOOL_CALL = // language=JSON
            """
            {"jsonrpc":"2.0","id":1,"method":"tools/call",
             "params":{"name":"slow-progress","arguments":{},"_meta":{"progressToken":"tok-1"}}}
            """;

    // silent-comment request carries NO progress token — the handler keeps the connection alive
    // with ctx.notifications().comment(...), the token-free keep-alive.
    @Language("json")
    private static final String COMMENT_CALL = // language=JSON
            """
            {"jsonrpc":"2.0","id":1,"method":"tools/call",
             "params":{"name":"silent-comment","arguments":{}}}
            """;

    private final ServerEngine server = createServer();

    private NettyServer nettyServer;
    private int port;

    private static ServerEngine createServer() {
        var server = TachyonServer.builder()
                .session(s -> s.enabled())
                .network(n -> n.heartbeatInterval(HEARTBEAT))
                .build();
        var warmup = new ProgressHandler("warmup", 0);
        var slowProgress = new ProgressHandler("slow-progress", SLOW_SLEEP_MS);
        var warmupComment = new CommentHandler("warmup-comment", 0);
        var silentComment = new CommentHandler("silent-comment", SLOW_SLEEP_MS);
        server.tools()
                .register(warmup.descriptor(), warmup::handle)
                .register(slowProgress.descriptor(), slowProgress::handle)
                .register(warmupComment.descriptor(), warmupComment::handle)
                .register(silentComment.descriptor(), silentComment::handle);
        return (ServerEngine) server;
    }

    @BeforeAll
    void startServer() throws Exception {
        var config = new NettyServerConfig(
                "127.0.0.1",
                0,
                "/mcp",
                READER_IDLE,
                Duration.ofMinutes(5),
                McpChannelInitializer.DEFAULT_MAX_CONTENT_LENGTH,
                NettyServerConfig.buildCorsConfig(null, false, false, null),
                null,
                NettyIoEngine.AUTO,
                null);
        nettyServer = new NettyServer(server, config);
        port = nettyServer.port();
        warmUp();
    }

    @AfterAll
    void stopServer() {
        nettyServer.close();
        server.close();
    }

    /**
     * JIT-warms the POST → dispatch → {@code doStart} → {@code SseHeartbeat.enable} path so the
     * timed tests flush their first progress event sub-millisecond and never lose the race against
     * the reader-idle timer.
     */
    private void warmUp() throws Exception {
        try (var client = new Mcp20251125Client(port)) {
            var sessionId = client.initialize();
            client.post(
                    sessionId, // language=JSON
                    """
                    {"jsonrpc":"2.0","id":1,"method":"tools/call",
                     "params":{"name":"warmup","arguments":{},"_meta":{"progressToken":"warmup"}}}
                    """);
            client.post(
                    sessionId, // language=JSON
                    """
                    {"jsonrpc":"2.0","id":2,"method":"tools/call",
                     "params":{"name":"warmup-comment","arguments":{}}}
                    """);
        }
    }

    @Test
    void progressNotificationUpgradesPostToSSE() throws Exception {
        callSlowProgressAndAssertSse();
    }

    @Test
    @Timeout(30)
    void progressKeepAliveEmitsHeartbeat() throws Exception {
        var lines = new CopyOnWriteArrayList<String>();
        try (var client = new Mcp20251125Client(port)) {
            var sessionId = client.initialize();
            var response = client.sendStreamingRequest(sessionId, TOOL_CALL);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("content-type").orElse("")).startsWith("text/event-stream");
            var consume = CompletableFuture.runAsync(() -> response.body().forEach(lines::add));
            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> assertThat(lines)
                            .as("scheduler must emit an SSE comment heartbeat, not close the channel")
                            .anyMatch(l -> l.startsWith(":")));
            consume.get(15, TimeUnit.SECONDS);
            var body = String.join("\n", lines);
            assertProgressTokenCorrelates(body);
            assertThat(body).contains("done");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Timeout(30)
    void commentKeepAliveUpgradesWithoutProgressToken(boolean modernProtocol) throws Exception {
        var lines = new CopyOnWriteArrayList<String>();
        try (var client = modernProtocol ? new Mcp20260728Client(port) : new Mcp20251125Client(port)) {
            final var sessionId = modernProtocol ? null : client.initialize();
            var response = client.sendStreamingRequest(sessionId, COMMENT_CALL);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("content-type").orElse("")).startsWith("text/event-stream");
            var consume = CompletableFuture.runAsync(() -> response.body().forEach(lines::add));
            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> assertThat(lines)
                            .as("an empty SSE comment must upgrade the POST and keep it alive with no progress token")
                            .anyMatch(l -> l.startsWith(":")));
            consume.get(15, TimeUnit.SECONDS);
            var body = String.join("\n", lines);
            assertThat(lines.stream().filter(line -> line.startsWith(":")).count())
                    .as("initial comment plus scheduled heartbeats")
                    .isGreaterThan(1);
            assertThatJsonRpcResponse(extractJsonRpcResponse(body, "1"))
                    .isSuccess()
                    .hasId(1)
                    .hasResult(modernProtocol ? """
                              {"content":[{"type":"text","text":"done"}],"resultType":"complete"}
                              """ : """
                              {"content":[{"type":"text","text":"done"}]}
                              """);
            // No progress token was sent, so no progress notification should appear — the comment
            // alone drove the upgrade and keep-alive.
            assertThat(body).doesNotContain("notifications/progress");
        }
    }

    @Test
    @Timeout(30)
    void sessionlessCommentStreamClosesOnReaderIdleWhenHeartbeatsDisabled() throws Exception {
        final var releaseTool = new CompletableFuture<Void>();
        final var lines = new CopyOnWriteArrayList<String>();
        try (final var isolatedServer = TachyonServer.builder()
                .network(n -> n.port(0).readerIdleTimeout(READER_IDLE).heartbeatInterval(Duration.ZERO))
                .build()) {
            isolatedServer
                    .tools()
                    .register(ToolDescriptor.builder().name("silent-comment").build(), (ctx, request) -> {
                        ctx.notifications().comment();
                        releaseTool.get(15, TimeUnit.SECONDS);
                        return ToolResult.text("done");
                    });
            isolatedServer.start();
            try (final var client = new Mcp20260728Client(isolatedServer.port())) {
                final var response = client.sendStreamingRequest(null, COMMENT_CALL);
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.headers().firstValue("content-type").orElse(""))
                        .startsWith("text/event-stream");
                try (final var body = response.body()) {
                    final var consume = CompletableFuture.runAsync(() -> body.forEach(lines::add));
                    assertThatThrownBy(() -> consume.get(5, TimeUnit.SECONDS))
                            .isInstanceOf(ExecutionException.class)
                            .hasRootCauseInstanceOf(IOException.class);
                    assertThat(lines.stream().filter(line -> line.startsWith(":")))
                            .as("only the tool's initial comment, no scheduled heartbeats")
                            .containsExactly(":");
                    assertThat(String.join("\n", lines)).doesNotContain("done", "\"result\"");
                }
            } finally {
                releaseTool.complete(null);
            }
        }
    }

    /**
     * Calls {@code slow-progress}, asserts the shared SSE-upgrade contract (200, event-stream
     * content type, progress notification, tool result) and returns the accumulated SSE body.
     */
    private void callSlowProgressAndAssertSse() throws Exception {
        try (var client = new Mcp20251125Client(port)) {
            var sessionId = client.initialize();
            var response = client.post(sessionId, TOOL_CALL);

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("content-type").orElse("")).startsWith("text/event-stream");
            var body = response.body();
            assertProgressTokenCorrelates(body);
            assertThat(body).contains("done");
            assertThat(body).doesNotContain("retry:");
        }
    }

    /**
     * Spec (basic/utilities/progress): a progress notification MUST only reference a token that was
     * provided in the originating request's {@code _meta.progressToken} (here, {@code "tok-1"} from
     * {@link #TOOL_CALL}). Asserting mere presence of "notifications/progress" would also pass for a
     * notification carrying the wrong or no token.
     */
    private static void assertProgressTokenCorrelates(String body) {
        var progressLine = body.lines()
                .filter(l -> l.contains("notifications/progress"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no notifications/progress line in: " + body));
        assertThat(progressLine).contains("\"progressToken\":\"tok-1\"");
    }

    /**
     * Token-driven keep-alive: forwards the client's {@code _meta.progressToken} to {@code progress()}.
     */
    private static class ProgressHandler extends AbstractToolHandler {

        private final long sleepMs;

        ProgressHandler(String name, long sleepMs) {
            super(ToolDescriptor.builder()
                    .name(name)
                    .description("Emits a progress notification then completes")
                    .build());
            this.sleepMs = sleepMs;
        }

        @Override
        public ToolResult handle(InteractionContext ctx, ToolRequest request) throws Exception {
            // Forward the client's requested progress token (request _meta.progressToken). progress()
            // requires a non-null token; the request supplies one. This server->client message
            // upgrades the POST to SSE so reader-idle can no longer reap it.
            ctx.notifications().progress(request.progressToken(), 1, 1, "tick");
            if (sleepMs > 0) Thread.sleep(sleepMs);
            return ToolResult.text("done");
        }
    }

    /**
     * Token-free keep-alive: emits an empty SSE comment, which upgrades the POST with no token.
     */
    private static class CommentHandler extends AbstractToolHandler {

        private final long sleepMs;

        CommentHandler(String name, long sleepMs) {
            super(ToolDescriptor.builder()
                    .name(name)
                    .description("Emits an empty SSE comment then completes")
                    .build());
            this.sleepMs = sleepMs;
        }

        @Override
        public ToolResult handle(InteractionContext ctx, ToolRequest request) throws Exception {
            // No progress token needed: an empty comment (: line) upgrades the POST to SSE and arms
            // the heartbeat, keeping the stream alive for the whole run.
            ctx.notifications().comment();
            if (sleepMs > 0) Thread.sleep(sleepMs);
            return ToolResult.text("done");
        }
    }
}
