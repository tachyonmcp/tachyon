/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.testkit.Mcp20251125Client;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpRequest;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * A request pipelined beyond {@code maxPipelinedRequests} is refused in order with {@code 429} and
 * never runs. The limit is {@code 0}, so the second request overflows the moment the gate sees it while
 * the first is in flight: reads are still on, since nothing is queued. A counter behind the codec
 * proves the gate has seen both requests before the first is released. Overflow past a non-zero limit
 * depends on how many requests one read decodes, so {@code HttpPipeliningGateTest} covers it.
 */
class HttpPipeliningLimitTest extends AbstractStatelessMcpE2eTest<Mcp20251125Client> {

    private final CountDownLatch decoded = new CountDownLatch(2);
    private final CountDownLatch slowStarted = new CountDownLatch(1);
    private final CompletableFuture<ToolResult> slowResult = new CompletableFuture<>();
    private final AtomicInteger fastCalls = new AtomicInteger();

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
                b -> b.capabilities(c -> c.tools())
                        .network(n -> n.maxPipelinedRequests(0))
                        .pipelineCustomizer(p -> p.addAfter("http", "decoded-requests", new DecodedRequests())),
                s -> s.tools()
                        .registerAsync(
                                d -> d.name("slow").description("Completes when the test releases it"),
                                (ctx, request) -> {
                                    slowStarted.countDown();
                                    return slowResult;
                                })
                        .register(d -> d.name("fast").description("Completes at once"), (ctx, request) -> {
                            fastCalls.incrementAndGet();
                            return ToolResult.text("fast");
                        }));
    }

    @Test
    void requestBeyondLimitIsRefusedInOrderWith429() throws Exception {
        try (var socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            var out = socket.getOutputStream();
            out.write((post(1, "slow") + post(2, "fast")).getBytes(StandardCharsets.UTF_8));
            out.flush();
            var in = socket.getInputStream();

            assertThat(slowStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(decoded.await(5, TimeUnit.SECONDS))
                    .as("the gate saw the second request while the first was in flight")
                    .isTrue();
            assertThat(fastCalls).hasValue(0);
            slowResult.complete(ToolResult.text("slow"));

            var first = CorsRawResponse.read(in);
            assertThat(first.status()).isEqualTo(200);
            assertThat(first.body()).contains("\"id\":1").contains("slow");

            var second = CorsRawResponse.read(in);
            assertThat(second.status()).as("the request over the limit").isEqualTo(429);
            assertThat(second.header("connection")).isEqualToIgnoringCase("close");
            assertThat(second.body()).isEqualTo("Too many pipelined requests");
            assertThat(in.read()).as("connection closed after the refusal").isEqualTo(-1);
            assertThat(fastCalls).as("the refused request never ran").hasValue(0);
        } finally {
            slowResult.complete(ToolResult.text("slow"));
        }
    }

    private String post(int id, String tool) {
        // language=JSON
        var body = """
                {"jsonrpc":"2.0","id":%d,"method":"tools/call","params":{"name":"%s","arguments":{}}}""".formatted(id, tool);
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        return "POST /mcp HTTP/1.1\r\n"
                + "Host: localhost:" + port + "\r\n"
                + "MCP-Protocol-Version: 2025-11-25\r\n"
                + "Content-Type: application/json\r\n"
                + "Accept: application/json, text/event-stream\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "\r\n"
                + body;
    }

    /** Counts each decoded request once the handlers after it, the pipelining gate first, have seen it. */
    private final class DecodedRequests extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            final var request = msg instanceof HttpRequest;
            ctx.fireChannelRead(msg);
            if (request) {
                decoded.countDown();
            }
        }
    }
}
