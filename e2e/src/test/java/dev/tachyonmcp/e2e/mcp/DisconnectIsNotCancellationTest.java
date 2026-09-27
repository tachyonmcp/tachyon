/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.features.tools.ToolFn;
import dev.tachyonmcp.api.server.features.tools.ToolRequest;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.server.config.SessionConfig;
import dev.tachyonmcp.testkit.Mcp20251125Client;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A client that goes away never interrupts a running tool (MCP 2025-11-25, Transports § Sending
 * Messages 6: disconnection SHOULD NOT be interpreted as cancellation; to cancel, the client sends
 * {@code notifications/cancelled}).
 *
 * <p>The tool may instead watch {@code context.responseUndeliverable()}, which completes once nobody
 * can ever receive its response:
 *
 * <ul>
 *   <li>stateless: when the connection closes, because nothing can resume the stream;
 *   <li>stateful: only when the session ends, because until then the client may resume.
 * </ul>
 */
class DisconnectIsNotCancellationTest {

    @ParameterizedTest(name = "stateful={0}")
    @ValueSource(booleans = {false, true})
    void clientDisconnectDoesNotInterruptTheTool(boolean stateful) throws Exception {
        var tool = new SlowTool();
        try (var server = startServer(stateful, tool);
                var client = new Mcp20251125Client(server.port())) {
            var sessionId = stateful ? client.initialize() : null;

            // 1. The client calls the tool, then drops the connection without reading the answer.
            callToolAndDisconnect(server.port(), sessionId, tool);

            if (sessionId != null) {
                // 2. The session outlives the connection, so the client could still resume the
                //    stream: the response is not undeliverable yet.
                assertThat(client.ping(sessionId, 2).statusCode())
                        .as("session is still alive")
                        .isEqualTo(200);
                assertThat(tool.undeliverableSignal)
                        .as("a dropped connection alone does not make a session response undeliverable")
                        .isNotDone();

                // 3. The session ends: now nobody can receive the response.
                assertThat(client.delete(sessionId).statusCode()).isEqualTo(200);
            }

            // 4. The tool gets the signal, on a server worker: dependents may block, which must
            //    never happen on the Netty event loop that saw the connection close.
            assertThat(tool.undeliverableSignal)
                    .as("tool gets the undeliverable signal")
                    .succeedsWithin(Duration.ofSeconds(5))
                    .satisfies(thread -> assertThat(thread.isVirtual())
                            .as("signal completed on %s", thread.getName())
                            .isTrue());

            // 5. The tool still finishes its work. Any interrupt from the disconnect or the session
            //    end would already have hit it while it waited in step 4.
            tool.finish.countDown();
            assertThat(tool.ended.await(5, SECONDS)).as("tool ended").isTrue();
            assertThat(tool.interrupted).as("tool was never interrupted").isFalse();
            assertThat(tool.completedWork).as("tool completed its work").isTrue();
        }
    }

    /** A tool that works until the test lets it finish, and records what happened to it. */
    private static final class SlowTool implements ToolFn {

        final CountDownLatch started = new CountDownLatch(1);
        final CompletableFuture<Thread> undeliverableSignal = new CompletableFuture<>();
        final CountDownLatch finish = new CountDownLatch(1);
        final CountDownLatch ended = new CountDownLatch(1);
        final AtomicBoolean interrupted = new AtomicBoolean();
        final AtomicBoolean completedWork = new AtomicBoolean();

        @Override
        public ToolResult apply(InteractionContext context, ToolRequest request) throws Exception {
            context.responseUndeliverable().thenRun(() -> undeliverableSignal.complete(Thread.currentThread()));
            started.countDown();
            try {
                completedWork.set(finish.await(10, SECONDS));
                return ToolResult.text("done");
            } catch (InterruptedException e) {
                interrupted.set(true);
                throw e;
            } finally {
                ended.countDown();
            }
        }
    }

    private static TachyonServer startServer(boolean stateful, SlowTool tool) {
        var builder = TachyonServer.builder().port(0);
        if (stateful) builder.session(SessionConfig.Builder::enabled);
        var server = builder.build();
        server.tools().register(b -> b.name("slow"), tool);
        server.start();
        return server;
    }

    /**
     * Sends {@code tools/call} over a raw socket and closes it once the tool runs. The testkit
     * client always waits for the response, so it cannot walk away mid-call.
     */
    private static void callToolAndDisconnect(int port, @Nullable String sessionId, SlowTool tool) throws Exception {
        try (var socket = new Socket("localhost", port)) {
            socket.getOutputStream().write(toolCallRequest(sessionId));
            socket.getOutputStream().flush();
            assertThat(tool.started.await(5, SECONDS)).as("tool started").isTrue();
        }
    }

    private static byte[] toolCallRequest(@Nullable String sessionId) {
        // language=json
        var body = """
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"slow","arguments":{}}}
                """;
        return ("POST /mcp HTTP/1.1\r\nHost: localhost\r\n"
                        + "Content-Type: application/json\r\nAccept: application/json, text/event-stream\r\n"
                        + "MCP-Protocol-Version: 2025-11-25\r\n"
                        + (sessionId != null ? "MCP-Session-Id: " + sessionId + "\r\n" : "")
                        + "Content-Length: " + body.getBytes(UTF_8).length + "\r\n\r\n" + body)
                .getBytes(UTF_8);
    }
}
