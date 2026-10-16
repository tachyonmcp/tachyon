/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty;

import static dev.tachyonmcp.core.test.TestUtils.newEngine;

import dev.tachyonmcp.api.server.security.SecurityContext;
import dev.tachyonmcp.core.server.config.BearerConfig;
import dev.tachyonmcp.core.server.config.NetworkConfig;
import dev.tachyonmcp.core.server.config.SecurityConfig;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.server.security.AccessPolicy;
import dev.tachyonmcp.core.server.security.ProtectedResourceMetadata;
import dev.tachyonmcp.core.transport.netty.http.AcceptValidationHandler;
import dev.tachyonmcp.core.transport.netty.http.AuthenticationHandler;
import dev.tachyonmcp.core.transport.netty.http.ContentTypeValidationHandler;
import dev.tachyonmcp.core.transport.netty.http.CorsHttpObjectAggregator;
import dev.tachyonmcp.core.transport.netty.http.CorsPreflightHandler;
import dev.tachyonmcp.core.transport.netty.http.DnsRebindingProtectionHandler;
import dev.tachyonmcp.core.transport.netty.http.EndpointValidatorHandler;
import dev.tachyonmcp.core.transport.netty.http.HttpPipeliningGate;
import dev.tachyonmcp.core.transport.netty.http.McpHeaderGuardHandler;
import dev.tachyonmcp.core.transport.netty.http.ProtectedResourceMetadataHandler;
import dev.tachyonmcp.core.transport.netty.http.TachyonCorsHandler;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerKeepAliveHandler;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
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
 * Per-request cost of the HTTP request path in front of dispatch: every handler a POST crosses from
 * the pipelining gate to {@code interaction}, wired as in {@code McpChannelInitializer}, then a sink
 * that answers {@code 200}. {@link #authOff()} is the pipeline of a server without authentication;
 * {@link #authOn()} adds the authentication handlers and the Protected Resource Metadata handler in
 * their real positions, with a provider that accepts at once on the caller's thread, so the figure is the handlers' own cost and not a thread
 * hand-off. The request carries a small body in a separate content message, as the codec delivers it.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class HttpRequestPathBenchmark {

    private static final byte[] BODY =
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}".getBytes(StandardCharsets.UTF_8);

    private static final BearerConfig BEARER = BearerConfig.builder()
            .resource(URI.create("https://mcp.example.test/mcp"))
            .authorizationServers(URI.create("https://auth.example.test"))
            .scopesSupported("tools:call")
            .verifier(token -> {
                throw new IllegalStateException("never called: the provider below accepts at once");
            })
            .build();

    private ServerEngine plainServer;
    private ServerEngine authenticatingServer;
    private EmbeddedChannel plain;
    private EmbeddedChannel authenticated;
    private HttpHeaders headers;

    @Setup(Level.Trial)
    public void setUp() {
        headers = new DefaultHttpHeaders()
                .set(HttpHeaderNames.HOST, "localhost:8080")
                .set(HttpHeaderNames.CONTENT_TYPE, "application/json")
                .set(HttpHeaderNames.ACCEPT, "application/json, text/event-stream")
                .set(HttpHeaderNames.CONTENT_LENGTH, BODY.length)
                .set("MCP-Protocol-Version", "2025-11-25")
                .set(HttpHeaderNames.AUTHORIZATION, "Bearer bench-token");
        plainServer = newEngine(b -> {});
        authenticatingServer = newEngine(b -> b.security(s -> s.accessPolicy(AccessPolicy.permitAll())
                .authenticationProvider(request -> SecurityContext.authenticated(() -> "alice", List.of()))));
        plain = new EmbeddedChannel(pipeline(false, plainServer).toArray(ChannelHandler[]::new));
        authenticated = new EmbeddedChannel(pipeline(true, authenticatingServer).toArray(ChannelHandler[]::new));
        if (post(plain) != HttpResponseStatus.OK || post(authenticated) != HttpResponseStatus.OK) {
            throw new IllegalStateException("benchmark request was rejected");
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        plain.finishAndReleaseAll();
        authenticated.finishAndReleaseAll();
        plainServer.close();
        authenticatingServer.close();
    }

    @Benchmark
    public Object authOff() {
        return post(plain);
    }

    @Benchmark
    public Object authOn() {
        return post(authenticated);
    }

    private HttpResponseStatus post(EmbeddedChannel channel) {
        channel.writeInbound(
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/mcp", headers),
                new DefaultHttpContent(Unpooled.wrappedBuffer(BODY)),
                LastHttpContent.EMPTY_LAST_CONTENT);
        final FullHttpResponse response = channel.readOutbound();
        final var status = response.status();
        response.release();
        return status;
    }

    private static List<ChannelHandler> pipeline(boolean authentication, ServerEngine server) {
        final var handlers = new ArrayList<ChannelHandler>();
        final var gate = new HttpPipeliningGate(NetworkConfig.DEFAULT_MAX_PIPELINED_REQUESTS);
        handlers.add(gate);
        handlers.add(new HttpServerKeepAliveHandler());
        handlers.add(new DnsRebindingProtectionHandler(List.of()));
        handlers.add(
                authentication
                        ? new EndpointValidatorHandler("/mcp", ProtectedResourceMetadata.paths(BEARER.resource()))
                        : new EndpointValidatorHandler("/mcp"));
        handlers.add(new CorsPreflightHandler());
        handlers.add(new TachyonCorsHandler(NettyServerConfig.defaultCorsConfig()));
        if (authentication) {
            handlers.add(new ProtectedResourceMetadataHandler(BEARER));
            handlers.add(new AuthenticationHandler(
                    new SecurityConfig(
                            new SecurityConfig.Authentication.Custom(
                                    request -> SecurityContext.authenticated(() -> "alice", List.of("tools:call"))),
                            AccessPolicy.permitAll(),
                            SecurityConfig.DEFAULT_AUTHENTICATION_TIMEOUT,
                            SecurityConfig.DEFAULT_MAX_PENDING_AUTHENTICATIONS),
                    Runnable::run,
                    gate,
                    List.of(),
                    new Semaphore(SecurityConfig.DEFAULT_MAX_PENDING_AUTHENTICATIONS)));
        }
        handlers.add(McpHeaderGuardHandler.INSTANCE);
        handlers.add(new ProtocolVersionHandler(false));
        handlers.add(AcceptValidationHandler.INSTANCE);
        handlers.add(ContentTypeValidationHandler.INSTANCE);
        handlers.add(new CorsHttpObjectAggregator(McpChannelInitializer.DEFAULT_MAX_CONTENT_LENGTH));
        handlers.add(new UnsupportedProtocolVersionHandler());
        handlers.add(new InteractionHandler());
        handlers.add(new Answer(server));
        return handlers;
    }

    /**
     * Ends the pipeline as the operation handler does: takes the caller of the request, then releases
     * the aggregated request and answers it, which frees the gate.
     */
    private static final class Answer extends ChannelInboundHandlerAdapter {

        private final ServerEngine server;

        Answer(ServerEngine server) {
            this.server = server;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof FullHttpRequest) {
                if (ChannelHandlerUtils.takeCaller(ctx, server) == null) {
                    throw new IllegalStateException("no authenticated caller");
                }
                final var response =
                        new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.EMPTY_BUFFER);
                response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
                ctx.writeAndFlush(response);
            }
            ReferenceCountUtil.release(msg);
        }
    }
}
