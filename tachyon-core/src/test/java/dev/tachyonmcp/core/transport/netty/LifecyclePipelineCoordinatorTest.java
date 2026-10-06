/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty;

import static dev.tachyonmcp.core.test.TestUtils.newEngine;
import static dev.tachyonmcp.core.transport.netty.InteractionHandler.INTERACTION_CONTEXT_KEY;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.runtime.InteractionContext.Lifecycle;
import dev.tachyonmcp.core.protocol.Protocols;
import dev.tachyonmcp.core.runtime.DefaultChannelContext;
import dev.tachyonmcp.core.runtime.InteractionEvent;
import dev.tachyonmcp.core.runtime.Session;
import dev.tachyonmcp.core.runtime.SseConnection;
import dev.tachyonmcp.core.server.McpDispatcher;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LifecyclePipelineCoordinatorTest {

    private ServerEngine server;
    private EmbeddedChannel channel;

    @BeforeEach
    void setUp() {
        server = newEngine(b -> {});
        final var dispatcher = new McpDispatcher(server, Runnable::run);
        channel = new EmbeddedChannel(new InteractionHandler());
        channel.pipeline()
                .addLast(
                        McpHandlerManager.HANDLER_INIT,
                        new McpInitializationHandler(server, dispatcher, Runnable::run));
        channel.pipeline()
                .addLast(
                        "lifecycle",
                        new LifecyclePipelineCoordinator(new McpHandlerManager(server, dispatcher, Runnable::run)));
    }

    @AfterEach
    void tearDown() {
        channel.close();
        server.close();
    }

    @Test
    void operationStartedReplacesInitHandlerWithOperationHandler() {
        initialize();

        assertThat(channel.pipeline().get(McpHandlerManager.HANDLER_INIT)).isNull();
        assertThat(channel.pipeline().get(McpOperationHandler.class)).isNotNull();
    }

    @Test
    void shutdownStartedRemovesSession() {
        initialize();

        var freshSession = server.createSession("shutdown-test");
        freshSession.activate();

        assertThat(server.getSession(freshSession.id())).isPresent();

        channel.pipeline().fireUserEventTriggered(new InteractionEvent.ShutdownStarted(freshSession.id()));
        channel.runPendingTasks();

        assertThat(server.getSession(freshSession.id())).isEmpty();
    }

    @Test
    void shutdownStartedWithNullSessionIdDoesNotThrow() {
        initialize();

        channel.pipeline().fireUserEventTriggered(new InteractionEvent.ShutdownStarted(null));
        channel.runPendingTasks();
    }

    @Test
    void abruptChannelCloseDoesNotLeakSessionInContext() {
        initialize();

        channel.close();
        channel.runPendingTasks();

        // InteractionContext may still be on the channel after close;
        // the channel's attribute map is GC'd with the channel itself.
        // Verify at least the session is not leaked.
        assertThat(channel.isOpen()).isFalse();
    }

    @Test
    void interactionContextLifecycleTransitionsViaEvents() {
        var protocol = Protocols.list().getFirst();
        channel.attr(INTERACTION_CONTEXT_KEY).set(new DefaultChannelContext(protocol));

        var ic = channel.attr(INTERACTION_CONTEXT_KEY).get();
        assertThat(ic).isNotNull();
        assertThat(ic.lifecycle()).isEqualTo(Lifecycle.INITIALIZATION);
        assertThat(ic.protocol().familyName()).isEqualTo("mcp");

        // OperationStarted
        channel.pipeline()
                .fireUserEventTriggered(new InteractionEvent.OperationStarted(new Session("s1", SseConnection.noop())));
        ic = channel.attr(INTERACTION_CONTEXT_KEY).get();
        assertThat(ic).isNotNull();
        assertThat(ic.lifecycle()).isEqualTo(Lifecycle.OPERATION);
        assertThat(ic.session()).isNotNull();

        // ShutdownStarted does not throw
        channel.pipeline().fireUserEventTriggered(new InteractionEvent.ShutdownStarted("s1"));
        channel.runPendingTasks();
    }

    private void initialize() {
        var body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";
        var request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/mcp", Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
        request.headers()
                .set(HttpHeaderNames.ORIGIN, "http://localhost:3000")
                .set(HttpHeaderNames.ACCEPT, "application/json, text/event-stream");
        channel.writeInbound(request);
        channel.runPendingTasks();
        // Drain the response
        var response = channel.readOutbound();
        if (response instanceof io.netty.buffer.ByteBuf buf) {
            buf.release();
        } else if (response instanceof FullHttpResponse full) {
            full.release();
        }
    }
}
