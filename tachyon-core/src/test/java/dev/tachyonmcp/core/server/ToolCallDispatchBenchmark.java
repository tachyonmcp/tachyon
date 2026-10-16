/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server;

import static dev.tachyonmcp.core.test.TestUtils.newEngine;

import dev.tachyonmcp.api.server.domain.RequestId;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.api.server.security.SecurityContext;
import dev.tachyonmcp.core.protocol.Protocols;
import dev.tachyonmcp.core.runtime.ChannelContext;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.server.security.AccessPolicy;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
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
 * Cost of dispatching one {@code tools/call} end to end, from the parsed request to the encoded
 * response, with no transport: a tool that declares no required scopes ({@code open}) against one that
 * declares one the caller holds ({@code guarded}). The difference is the scope check; {@code open} is
 * the figure every existing tool pays and must not move.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class ToolCallDispatchBenchmark {

    @Param({"open", "guarded"})
    public String tool;

    private ServerEngine server;
    private McpDispatcher dispatcher;
    private ChannelContext channel;
    private SecurityContext caller;
    private Map<String, Object> params;

    @Setup
    public void setUp() {
        server = newEngine(
                b -> b.stateless()
                        .capabilities(c -> c.tools())
                        .security(s -> s.accessPolicy(AccessPolicy.permitAll())
                                .authenticationProvider(request -> SecurityContext.anonymous())),
                s -> {
                    s.tools().register(d -> d.name("open"), (ctx, request) -> ToolResult.text("ok"));
                    s.tools()
                            .register(
                                    d -> d.name("guarded").requiredScopes("tools:call"),
                                    (ctx, request) -> ToolResult.text("ok"));
                });
        dispatcher = new McpDispatcher(server, server.executor());
        channel = Protocols.baseline().createInteractionContext();
        caller = SecurityContext.authenticated(() -> "alice", Set.of("tools:read", "tools:call"));
        params = Map.of("name", tool, "arguments", Map.of());
        if (!(call() instanceof McpDispatcher.DispatchResult.Response response)
                || !response.responseBodyString().contains("\"ok\"")) {
            throw new IllegalStateException("benchmark call was not served");
        }
    }

    @TearDown
    public void tearDown() {
        server.close();
    }

    @Benchmark
    public Object call() {
        return dispatcher
                .dispatchRequestAsync(
                        RequestId.of(1),
                        "tools/call",
                        params,
                        null,
                        null,
                        channel,
                        caller,
                        CompletableFuture.completedFuture(null))
                .join();
    }
}
