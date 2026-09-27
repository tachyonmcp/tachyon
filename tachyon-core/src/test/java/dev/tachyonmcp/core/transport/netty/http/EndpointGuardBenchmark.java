/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Per-request cost of the request-line guards every MCP request crosses: {@code dns-rebinding} then
 * {@code mcp-endpoint}, wired as in {@code McpChannelInitializer}. The request passes both, so the
 * accept path is measured, not rejection.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class EndpointGuardBenchmark {

    private EmbeddedChannel channel;
    private ChannelPipeline pipeline;
    private HttpHeaders headers;
    private final Sink sink = new Sink();

    @Setup(Level.Trial)
    public void setUp() {
        channel = new EmbeddedChannel(
                new DnsRebindingProtectionHandler(List.of("host.docker.internal")),
                new EndpointValidatorHandler("/mcp"),
                sink);
        pipeline = channel.pipeline();
        headers = new DefaultHttpHeaders().set(HttpHeaderNames.HOST, "localhost:8080");
        if (originForm() == null || absoluteForm() == null || !channel.isOpen()) {
            throw new IllegalStateException("benchmark request was rejected");
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        channel.finishAndReleaseAll();
    }

    @Benchmark
    public Object originForm() {
        return pass("/mcp?client=bench");
    }

    @Benchmark
    public Object absoluteForm() {
        return pass("http://localhost:8080/mcp?client=bench");
    }

    private Object pass(String uri) {
        pipeline.fireChannelRead(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, uri, headers));
        return sink.last;
    }

    private static final class Sink extends ChannelInboundHandlerAdapter {
        private Object last;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            last = msg;
        }
    }
}
