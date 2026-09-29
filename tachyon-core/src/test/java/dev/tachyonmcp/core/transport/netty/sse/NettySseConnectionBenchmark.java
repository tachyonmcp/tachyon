/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty.sse;

import dev.tachyonmcp.core.runtime.SseEvent;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Per-event cost of a live GET-SSE {@code send} on a writable channel: measure, queue, encode,
 * write and flush. The sink stands in for the socket: it releases each frame and completes its
 * promise, so the channel never turns unwritable.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class NettySseConnectionBenchmark {

    @Param({"200", "8192"})
    public int dataSize;

    private EmbeddedChannel channel;
    private NettySseConnection connection;
    private SseEvent event;

    @Setup(Level.Trial)
    public void setUp() {
        channel = new EmbeddedChannel(new Sink());
        connection = new NettySseConnection(channel, () -> {}, false, Duration.ofSeconds(30), 64 * 1024);
        event = new SseEvent("42", "message", "{\"d\":\"" + "x".repeat(dataSize - 8) + "\"}");
        connection.send(event);
        if (!channel.isActive()) throw new IllegalStateException("benchmark stream was closed");
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        channel.finishAndReleaseAll();
    }

    @Benchmark
    public void send() {
        connection.send(event);
    }

    private static final class Sink extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            ReferenceCountUtil.release(msg);
            promise.setSuccess();
        }
    }
}
