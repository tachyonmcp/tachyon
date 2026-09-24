/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.testkit.Mcp20251125Client;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * HTTP/1.1 pipelined responses follow request order (RFC 9112 §9.3.2) whatever answers them: a
 * validation handler's synchronous error, a POST-SSE stream, or a response that closes the
 * connection. A slow first request holds back a second one that would otherwise answer at once: the
 * second is not dispatched until the first response is complete, and not at all when that response
 * closes the connection.
 */
class HttpPipeliningOrderTest extends AbstractStatelessMcpE2eTest<Mcp20251125Client> {

    private final AtomicInteger fastCalls = new AtomicInteger();
    private volatile CountDownLatch slowStarted = new CountDownLatch(1);
    private volatile CompletableFuture<ToolResult> slowResult = new CompletableFuture<>();

    @Override
    protected Mcp20251125Client createTestClient() {
        return createTestClient(port);
    }

    @Override
    protected Mcp20251125Client createTestClient(int port) {
        return new Mcp20251125Client(port);
    }

    @Override
    protected void startDefaultServer() {
        startServer(
                b -> b.capabilities(c -> c.tools()),
                s -> s.tools()
                        .registerAsync(
                                d -> d.name("slow").description("Completes when the test releases it"),
                                (ctx, request) -> {
                                    slowStarted.countDown();
                                    return slowResult;
                                })
                        .registerAsync(
                                d -> d.name("slow-sse").description("Opens a POST-SSE stream, then waits"),
                                (ctx, request) -> {
                                    ctx.notifications().comment();
                                    slowStarted.countDown();
                                    return slowResult;
                                })
                        .register(d -> d.name("fast").description("Completes at once"), (ctx, request) -> {
                            fastCalls.incrementAndGet();
                            return ToolResult.text("fast");
                        }));
    }

    @BeforeEach
    void resetControls() {
        fastCalls.set(0);
        slowStarted = new CountDownLatch(1);
        slowResult = new CompletableFuture<>();
    }

    @AfterEach
    void releaseSlow() {
        slowResult.complete(ToolResult.text("slow"));
    }

    @Test
    void validationErrorWaitsForEarlierAsyncResponse() throws Exception {
        try (var socket = pipeline(post(1, "slow", "") + post(2, "fast", "", "text/plain"))) {
            var in = socket.getInputStream();
            assertThat(slowStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertSilent(in);
            slowResult.complete(ToolResult.text("slow"));

            var first = CorsRawResponse.read(in);
            assertThat(first.status()).isEqualTo(200);
            assertThat(first.body()).contains("\"id\":1").contains("slow");

            var second = CorsRawResponse.read(in);
            assertThat(second.status()).as("the rejected second request").isEqualTo(415);
            assertThat(second.header("connection")).isEqualToIgnoringCase("close");
            assertThat(in.read()).as("connection closed after the 415").isEqualTo(-1);
            assertThat(fastCalls).hasValue(0);
        }
    }

    @Test
    void postSseStreamIsNotInterleavedWithPipelinedRequest() throws Exception {
        try (var socket = pipeline(post(1, "slow-sse", "") + post(2, "fast", ""))) {
            var in = socket.getInputStream();
            assertThat(slowStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertNotDispatched();
            slowResult.complete(ToolResult.text("slow"));

            var first = CorsRawResponse.read(in);
            assertThat(first.status()).isEqualTo(200);
            assertThat(first.header("content-type")).startsWith("text/event-stream");
            assertThat(first.header("transfer-encoding")).isEqualToIgnoringCase("chunked");
            assertThat(first.header("connection")).isEqualToIgnoringCase("close");
            assertThat(first.body()).contains("\"id\":1").doesNotContain("\"id\":2");
            assertThat(in.read()).as("connection closed after the stream").isEqualTo(-1);
            assertThat(fastCalls)
                    .as("a request pipelined behind a closing stream runs no handler, so it is safe to retry")
                    .hasValue(0);
        }
    }

    @Test
    void requestPipelinedAfterConnectionCloseIsNeverDispatched() throws Exception {
        try (var socket = pipeline(post(1, "slow", "Connection: close\r\n") + post(2, "fast", ""))) {
            var in = socket.getInputStream();
            assertThat(slowStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertNotDispatched();
            slowResult.complete(ToolResult.text("slow"));

            var first = CorsRawResponse.read(in);
            assertThat(first.status()).isEqualTo(200);
            assertThat(first.body()).contains("\"id\":1");
            assertThat(first.header("connection")).isEqualToIgnoringCase("close");
            assertThat(in.read())
                    .as("connection closed after the first response")
                    .isEqualTo(-1);
            assertThat(fastCalls).as("the second request runs no handler").hasValue(0);
        }
    }

    private void assertSilent(InputStream in) {
        await().during(Duration.ofMillis(300))
                .atMost(Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(in.available())
                        .as("the second response must wait for the first")
                        .isZero());
    }

    private void assertNotDispatched() {
        await().during(Duration.ofMillis(300))
                .atMost(Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(fastCalls)
                        .as("the second request must wait for the first response")
                        .hasValue(0));
    }

    private Socket pipeline(String requests) throws Exception {
        var socket = new Socket("localhost", port);
        socket.setSoTimeout(10_000);
        var out = socket.getOutputStream();
        out.write(requests.getBytes(StandardCharsets.UTF_8));
        out.flush();
        return socket;
    }

    private String post(int id, String tool, String extraHeaders) {
        return post(id, tool, extraHeaders, "application/json");
    }

    private String post(int id, String tool, String extraHeaders, String contentType) {
        // language=JSON
        var body = """
                {"jsonrpc":"2.0","id":%d,"method":"tools/call","params":{"name":"%s","arguments":{}}}""".formatted(id, tool);
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        return "POST /mcp HTTP/1.1\r\n"
                + "Host: localhost:" + port + "\r\n"
                + "MCP-Protocol-Version: 2025-11-25\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Accept: application/json, text/event-stream\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + extraHeaders
                + "\r\n"
                + body;
    }
}
