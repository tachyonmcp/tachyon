/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server;

import static dev.tachyonmcp.core.test.TestUtils.newEngine;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.server.domain.RequestId;
import dev.tachyonmcp.core.protocol.Protocol;
import dev.tachyonmcp.core.protocol.ProtocolRequestMapper;
import dev.tachyonmcp.core.protocol.ProtocolResponseMapper;
import dev.tachyonmcp.core.protocol.Protocols;
import dev.tachyonmcp.core.runtime.ChannelContext;
import dev.tachyonmcp.core.runtime.DefaultChannelContext;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.server.session.DefaultDispatchContext;
import dev.tachyonmcp.core.server.session.DispatchContext;
import io.netty.handler.codec.http.HttpRequest;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class McpDispatcherProtocolContextTest {

    /**
     * Fake protocol that records context-factory invocations.
     */
    private static final class RecordingProtocol implements Protocol {

        final AtomicInteger contextsCreated = new AtomicInteger();
        final AtomicReference<@Nullable ChannelContext> lastCreated = new AtomicReference<>();

        RecordingProtocol() {}

        @Override
        public String familyName() {
            return "fake";
        }

        @Override
        public String versionString() {
            return "2099-01-01";
        }

        @Override
        public boolean matches(HttpRequest request) {
            return true;
        }

        @Override
        public ProtocolResponseMapper responseMapper() {
            return Protocols.list().getFirst().responseMapper();
        }

        @Override
        public ProtocolRequestMapper requestMapper() {
            return Protocols.list().getFirst().requestMapper();
        }

        @Override
        public ChannelContext createInteractionContext() {
            contextsCreated.incrementAndGet();
            var ic = new DefaultChannelContext(this);
            lastCreated.set(ic);
            return ic;
        }
    }

    @Test
    void dispatchWrapsChannelContext() throws Exception {
        try (ServerEngine server =
                (ServerEngine) TachyonServer.builder().session(s -> s.enabled()).build()) {
            var protocol = new RecordingProtocol();
            var session = server.createSession("sess_p1");
            session.activate();

            var handlerContext = new AtomicReference<@Nullable DispatchContext>();
            server.registerHandler(new RpcMethodHandler<>() {
                @Override
                public String method() {
                    return "test/capture";
                }

                @Override
                public Object decode(DispatchContext context, Object rawParams) {
                    return rawParams;
                }

                @Override
                public Object handle(DispatchContext context, Object params) {
                    handlerContext.set(context);
                    return Map.of("ok", true);
                }
            });

            var dispatcher = new McpDispatcher(server, Runnable::run);
            var channelContext = protocol.createInteractionContext();
            var result = dispatcher
                    .dispatchRequestAsync(RequestId.of(1), "test/capture", Map.of(), "sess_p1", null, channelContext)
                    .get(5, TimeUnit.SECONDS);

            assertThat(result).isInstanceOf(McpDispatcher.DispatchResult.Response.class);
            assertThat(protocol.contextsCreated).hasValue(1);
            assertThat(handlerContext.get())
                    .as("handler must receive a DispatchContext wrapping the channel context")
                    .isNotNull()
                    .isInstanceOf(DefaultDispatchContext.class);
            assertThat(protocol.lastCreated).hasValue(channelContext);
            assertThat(handlerContext.get()).isNotNull();
            assertThat(handlerContext.get().protocol()).isSameAs(protocol);
            assertThat(handlerContext.get().session()).isSameAs(session);
            assertThat(session.protocol()).isSameAs(protocol);
        }
    }

    @Test
    void dispatchWorksWithBareChannelContext() throws Exception {
        try (ServerEngine server = newEngine(b -> {})) {
            var protocol = new RecordingProtocol();
            server.createSession("sess_p2").activate();

            var dispatcher = new McpDispatcher(server, Runnable::run);
            var channelContext = protocol.createInteractionContext();

            var result = dispatcher
                    .dispatchRequestAsync(RequestId.of(1), "ping", Map.of(), "sess_p2", null, channelContext)
                    .get(5, TimeUnit.SECONDS);

            assertThat(result).isInstanceOf(McpDispatcher.DispatchResult.Response.class);
        }
    }

    @Test
    void statelessDispatchHasNoSession() throws Exception {
        try (ServerEngine server = newEngine(b -> {})) {
            var handlerContext = new AtomicReference<@Nullable DispatchContext>();
            server.registerHandler("test/capture", new RpcMethodHandler<>() {
                @Override
                public String method() {
                    return "test/capture";
                }

                @Override
                public Object decode(DispatchContext context, Object rawParams) {
                    return rawParams;
                }

                @Override
                public Object handle(DispatchContext context, Object params) {
                    handlerContext.set(context);
                    return Map.of("ok", true);
                }
            });

            var dispatcher = new McpDispatcher(server, Runnable::run);
            var result = dispatcher
                    .dispatchRequestAsync(RequestId.of(1), "test/capture", Map.of(), null)
                    .get(5, TimeUnit.SECONDS);

            assertThat(result).isInstanceOf(McpDispatcher.DispatchResult.Response.class);
            var context = handlerContext.get();
            assertThat(context).isNotNull();
            assertThat(context.session())
                    .as("stateless dispatch must not fabricate a session")
                    .isNull();
            assertThat(context.getLoggingLevel()).isNull();
            assertThat(context.protocol().familyName()).isEqualTo("mcp");
            assertThat(context.sendRequest("sampling/createMessage", Map.of()))
                    .as("server-to-client requests without a session must fail fast")
                    .isCompletedExceptionally();
        }
    }
}
