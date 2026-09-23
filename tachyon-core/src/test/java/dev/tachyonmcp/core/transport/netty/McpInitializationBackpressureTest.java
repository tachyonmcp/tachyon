/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.McpDispatcher;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.transport.netty.sse.SseHeartbeat;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.ReferenceCountUtil;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class McpInitializationBackpressureTest {
    private static final Duration READER_IDLE = Duration.ofMillis(500);
    private final CompletableFuture<ToolResult> result = new CompletableFuture<>();
    private ServerEngine server;
    private EmbeddedChannel channel;

    @AfterEach
    void close() {
        result.complete(ToolResult.text("done"));
        if (channel != null) channel.finishAndReleaseAll();
        if (server != null) server.close();
    }

    @Test
    void sustainedBackpressureClosesHeartbeatStreamOnWriterIdle() {
        startStream(Duration.ofSeconds(1));
        setWritable(false);
        advance(Duration.ofMillis(999));
        assertThat(channel.isOpen()).isTrue();
        assertThat(SseHeartbeat.isEnabled(channel)).isTrue();
        assertThat((Object) channel.readOutbound())
                .as("heartbeats skip backpressured channel")
                .isNull();
        advance(Duration.ofMillis(1));
        assertThat(channel.isOpen()).isFalse();
        assertThat(result).isNotDone();
    }

    @Test
    void readerIdleAloneKeepsHeartbeatStreamOpenWhenWriterIdleDisabled() {
        startStream(Duration.ZERO);
        setWritable(false);
        advance(Duration.ofMinutes(10));
        assertThat(channel.isOpen())
                .as("zero writerIdleTimeout disables the stall close; reader idle is ignored with heartbeats")
                .isTrue();
        assertThat(SseHeartbeat.isEnabled(channel)).isTrue();
    }

    @Test
    void heartbeatsKeepWritableStreamOpenPastWriterIdle() {
        startStream(Duration.ofSeconds(1));
        advance(Duration.ofSeconds(3));
        assertThat(channel.isOpen()).isTrue();
        assertThat((Object) channel.readOutbound()).as("scheduled heartbeat").isNotNull();
    }

    @Test
    void recoveryGivesNextBackpressureFullBudget() {
        startStream(Duration.ofSeconds(1));
        setWritable(false);
        advance(Duration.ofMillis(750));
        setWritable(true);
        advance(Duration.ofSeconds(2));
        assertThat(channel.isOpen()).isTrue();
        drain();
        setWritable(false);
        advance(Duration.ofMillis(850));
        assertThat(channel.isOpen()).isTrue();
        advance(Duration.ofMillis(200));
        assertThat(channel.isOpen()).isFalse();
    }

    private void startStream(Duration writerIdle) {
        server = (ServerEngine) TachyonServer.builder()
                .network(n -> n.heartbeatInterval(Duration.ofMillis(100)))
                .build();
        server.tools().registerAsync(b -> b.name("blocked"), (ctx, request) -> {
            ctx.notifications().comment();
            return result;
        });
        channel = new EmbeddedChannel(
                new ProtocolVersionHandler("/mcp"),
                new InteractionHandler(),
                new IdleStateHandler(READER_IDLE.toMillis(), writerIdle.toMillis(), 0, TimeUnit.MILLISECONDS),
                new McpInitializationHandler(server, new McpDispatcher(server, Runnable::run), Runnable::run));
        channel.freezeTime();
        final var request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/mcp", Unpooled.copiedBuffer("""
                        {"jsonrpc":"2.0","id":1,"method":"tools/call",
                         "params":{"name":"blocked","arguments":{}}}
                        """, StandardCharsets.UTF_8));
        request.headers()
                .set("MCP-Protocol-Version", "2026-07-28")
                .set(HttpHeaderNames.ACCEPT, "application/json, text/event-stream");
        channel.writeInbound(request);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            channel.runPendingTasks();
            assertThat(SseHeartbeat.isEnabled(channel)).isTrue();
        });
        drain();
    }

    private void setWritable(boolean writable) {
        channel.unsafe().outboundBuffer().setUserDefinedWritability(1, writable);
        channel.runPendingTasks();
        assertThat(channel.isWritable()).isEqualTo(writable);
    }

    private void advance(Duration duration) {
        channel.advanceTimeBy(duration.toNanos(), TimeUnit.NANOSECONDS);
        channel.runScheduledPendingTasks();
        channel.runPendingTasks();
    }

    private void drain() {
        Object message;
        while ((message = channel.readOutbound()) != null) ReferenceCountUtil.release(message);
    }
}
