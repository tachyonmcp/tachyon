/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.transport.netty.ChannelHandlerUtils;
import dev.tachyonmcp.testkit.Mcp20251125Client;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.util.concurrent.SingleThreadEventExecutor;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PostSseSlowReaderTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void parksProducerForNonReadingClientUntilIdleTimeoutClosesIt(boolean blockEventLoop) throws Exception {
        final var channel = new AtomicReference<@Nullable Channel>();
        final var producer = new AtomicReference<@Nullable Thread>();
        final var emitted = new CountDownLatch(1);
        final var eventLoopBlocked = new CountDownLatch(1);
        final var releaseEventLoop = new CountDownLatch(1);
        final var result = new CompletableFuture<ToolResult>();
        try (final var server = TachyonServer.builder()
                .network(n -> n.port(0).writerIdleTimeout(Duration.ofSeconds(1)))
                .runtime(r -> r.shutdownGracePeriod(Duration.ofMillis(100)))
                .pipelineCustomizer(p -> {
                    p.channel().config().setOption(ChannelOption.SO_SNDBUF, 1024);
                    channel.compareAndSet(null, p.channel());
                })
                .build()) {
            server.tools().registerAsync(b -> b.name("stream"), (context, request) -> {
                producer.set(Thread.currentThread());
                try {
                    if (blockEventLoop) {
                        channel.get().eventLoop().execute(() -> {
                            eventLoopBlocked.countDown();
                            try {
                                releaseEventLoop.await(10, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        });
                        if (!eventLoopBlocked.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Event loop did not reach barrier");
                        }
                    }
                    final var message = "x".repeat(8192);
                    for (int i = 0; i < 4096; i++) {
                        context.notifications().progress(request.progressToken(), i, 4096, message);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return CompletableFuture.failedFuture(e);
                } finally {
                    emitted.countDown();
                }
                return result;
            });
            server.start();
            try (final var socket = new Socket()) {
                socket.setReceiveBufferSize(1024);
                socket.connect(new InetSocketAddress("127.0.0.1", server.port()));
                socket.getOutputStream().write(toolCall().getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().flush();

                await().atMost(Duration.ofSeconds(10))
                        .untilAsserted(() -> assertThat(producer.get())
                                .as("the producer waits for the client instead of buffering")
                                .extracting(Thread::getState)
                                .isEqualTo(Thread.State.WAITING));
                assertThat(emitted.getCount()).isOne();
                final var slowChannel = channel.get();
                if (blockEventLoop) {
                    assertThat(((SingleThreadEventExecutor) slowChannel.eventLoop()).pendingTasks())
                            .as("about 1 MiB of 8 KiB events, not thousands of tasks")
                            .isLessThan(160);
                    releaseEventLoop.countDown();
                }
                assertThat(slowChannel.closeFuture().await(5, TimeUnit.SECONDS))
                        .as("the writer idle timeout closes a client that stopped reading")
                        .isTrue();
                assertThat(emitted.await(5, TimeUnit.SECONDS))
                        .as("closing the channel releases the parked producer")
                        .isTrue();
                try (final var client = new Mcp20251125Client(server.port())) {
                    assertThat(client.ping(null, 2).statusCode()).isEqualTo(200);
                }
            } finally {
                releaseEventLoop.countDown();
                result.complete(ToolResult.empty());
            }
        }
    }

    @Test
    void deliversBurstToReadingClientWithoutClosingIt() throws Exception {
        final var channel = new AtomicReference<@Nullable Channel>();
        final var notifications = 4096;
        try (final var server = TachyonServer.builder()
                .network(n -> n.port(0))
                .pipelineCustomizer(p -> channel.compareAndSet(null, p.channel()))
                .build()) {
            server.tools().register(b -> b.name("stream"), (context, request) -> {
                final var message = "x".repeat(8192);
                for (int i = 0; i < notifications; i++) {
                    context.notifications().progress(request.progressToken(), i, notifications, message);
                }
                return ToolResult.text("done");
            });
            server.start();
            try (final var socket = new Socket()) {
                socket.setSoTimeout(20_000);
                socket.connect(new InetSocketAddress("127.0.0.1", server.port()));
                socket.getOutputStream().write(toolCall().getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().flush();

                final var response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

                assertThat(response).startsWith("HTTP/1.1 200").contains("text/event-stream");
                assertThat(countOf(response, "notifications/progress"))
                        .as("every notification of a burst reaches a client that keeps reading")
                        .isEqualTo(notifications);
                assertThat(response).contains("\"text\":\"done\"").endsWith("0\r\n\r\n");
                final var fastChannel = channel.get();
                assertThat(fastChannel.closeFuture().await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(ChannelHandlerUtils.closeFailure(fastChannel))
                        .as("a reading client must not trip the pending write limit")
                        .isNull();
            }
        }
    }

    private static String toolCall() {
        // language=json
        final var body = """
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
                  "name":"stream","arguments":{},"_meta":{"progressToken":"p"}}}
                """;
        return "POST /mcp HTTP/1.1\r\nHost: localhost\r\n"
                + "Content-Type: application/json\r\nAccept: application/json, text/event-stream\r\n"
                + "MCP-Protocol-Version: 2025-11-25\r\nContent-Length: "
                + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body;
    }

    private static int countOf(String text, String token) {
        var count = 0;
        for (var i = text.indexOf(token); i >= 0; i = text.indexOf(token, i + token.length())) count++;
        return count;
    }
}
