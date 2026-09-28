/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.features.subscriptions;

import static dev.tachyonmcp.core.test.TestUtils.newEngine;

import dev.tachyonmcp.api.server.domain.RequestId;
import dev.tachyonmcp.core.protocol.Protocol;
import dev.tachyonmcp.core.protocol.ProtocolRequestMapper.SubscriptionListenRequest;
import dev.tachyonmcp.core.protocol.ProtocolResponseMapper;
import dev.tachyonmcp.core.protocol.Protocols;
import dev.tachyonmcp.core.runtime.SseEvent;
import dev.tachyonmcp.core.server.OutboundSseStream;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * {@code subscriptions/listen} churn against a registry holding {@link #LIVE_SUBSCRIPTIONS}
 * tools-list listeners: activate + remove alone, and while a notifier fans out under the same lock.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class SubscriptionRegistryBenchmark {

    private static final int LIVE_SUBSCRIPTIONS = 64;
    private static final SubscriptionListenRequest FILTER =
            new SubscriptionListenRequest(true, false, false, Set.of(), Set.of());

    private ServerEngine engine;
    private SubscriptionRegistry registry;
    private ProtocolResponseMapper responseMapper;
    private final OutboundSseStream stream = new DiscardingStream();

    @Setup(Level.Trial)
    public void setUp() {
        engine = newEngine(b -> {});
        registry = new SubscriptionRegistry(engine);
        responseMapper = Protocols.list().stream()
                .map(Protocol::responseMapper)
                .filter(m -> m.supports("mcp", "2026-07-28"))
                .findFirst()
                .orElseThrow();
        for (int i = 0; i < LIVE_SUBSCRIPTIONS; i++) {
            registry.activate(RequestId.of(i), stream, FILTER, responseMapper, new CompletableFuture<>());
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        engine.close();
    }

    @Benchmark
    @Threads(4)
    public long activateRemove() {
        return activateAndRemove();
    }

    @Benchmark
    @Group("contended")
    @GroupThreads(3)
    public long contendedActivateRemove() {
        return activateAndRemove();
    }

    @Benchmark
    @Group("contended")
    @GroupThreads(1)
    public void contendedNotify() {
        registry.notifyToolsListChanged();
    }

    private long activateAndRemove() {
        var key = registry.activate(RequestId.of(-1L), stream, FILTER, responseMapper, new CompletableFuture<>());
        registry.remove(key);
        return key;
    }

    private static final class DiscardingStream implements OutboundSseStream {
        @Override
        public CompletionStage<Void> start() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public boolean started() {
            return true;
        }

        @Override
        public void writeEvent(@Nullable SseEvent event) {}

        @Override
        public void close() {}
    }
}
