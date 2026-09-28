/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty;

import static dev.tachyonmcp.core.test.TestUtils.newEngine;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.server.domain.RequestId;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.McpDispatcher;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.SAME_THREAD)
class NettyServerThreadingTest {

    @Test
    void eventLoopsAreOnPlatformThreadsAndToolHandlerOnVirtualThread() throws Exception {
        var handlerThread = new CompletableFuture<String>();

        try (var server = newEngine(
                        b -> {},
                        s -> s.tools().register(builder -> builder.name("thread_probe"), (ctx, request) -> {
                            Thread thread = Thread.currentThread();
                            handlerThread.complete(thread.getName() + " virtual:" + thread.isVirtual());
                            return ToolResult.empty();
                        }));
                var netty = new NettyServer(0, server)) {
            Callable<Thread> probe = Thread::currentThread;

            Thread workerThread = netty.eventLoopGroup.next().submit(probe).get(10, TimeUnit.SECONDS);
            assertThat(workerThread.isVirtual())
                    .as("worker event loop must run on a platform thread")
                    .isFalse();
            assertThat(workerThread.getName()).startsWith("netty-io-");

            // Drive the tool through the same dispatcher path the Netty handler uses,
            // bypassing the HTTP layer to keep the test focused on threading.
            var session = server.createSession("sess_thread-probe");
            session.activate();
            var dispatcher = new McpDispatcher(server, server.executor());
            var params = java.util.Map.of("name", "thread_probe", "arguments", java.util.Map.of());
            dispatcher
                    .dispatchRequestAsync(RequestId.of(1), "tools/call", params, "sess_thread-probe")
                    .join();

            String toolThread = handlerThread.get(10, TimeUnit.SECONDS);
            assertThat(toolThread)
                    .as("tool handler must run on a virtual thread")
                    .endsWith("virtual:true");
        }
    }

    @Test
    void customThreadFactoryAddsNamePrefix() throws Exception {
        var handlerThreadName = new CompletableFuture<String>();

        try (ServerEngine server = newEngine(
                b -> b.threadFactory(Thread.ofVirtual().name("tenant-", 0).factory()),
                s -> s.tools().register(builder -> builder.name("name_probe"), (ctx, request) -> {
                    handlerThreadName.complete(Thread.currentThread().getName());
                    return ToolResult.empty();
                }))) {
            server.createSession("sess-name").activate();
            var dispatcher = new McpDispatcher(server, server.executor());
            dispatcher
                    .dispatchRequestAsync(
                            RequestId.of(1),
                            "tools/call",
                            java.util.Map.of("name", "name_probe", "arguments", java.util.Map.of()),
                            "sess-name")
                    .join();

            String name = handlerThreadName.get(10, TimeUnit.SECONDS);
            assertThat(name).as("thread name must use tenant prefix").startsWith("tenant-");
        }
    }

    @Test
    void closeOnInterruptedThreadStillShutsDownEventLoopsAndKeepsInterrupt() {
        try (var server = newEngine(b -> {})) {
            var netty = new NettyServer(0, server);
            boolean interrupted;
            Thread.currentThread().interrupt();
            try {
                netty.close();
            } finally {
                interrupted = Thread.interrupted();
            }

            assertThat(interrupted).as("interrupt status restored").isTrue();
            assertThat(netty.eventLoopGroup.isTerminated())
                    .as("event loops shut down despite the interrupt")
                    .isTrue();
        }
    }

    @Test
    void serverOwnedExecutorIsShutDownByServerClose() {
        var server = newEngine(
                b -> b.threadFactory(Thread.ofVirtual().name("tenant-", 0).factory()),
                s -> s.tools().register(builder -> builder.name("exec_probe"), (ctx, request) -> ToolResult.empty()));
        var executor = server.executor();
        server.close();

        assertThat(executor.isShutdown())
                .as("server-owned executor must be shut down by server.close()")
                .isTrue();
    }
}
