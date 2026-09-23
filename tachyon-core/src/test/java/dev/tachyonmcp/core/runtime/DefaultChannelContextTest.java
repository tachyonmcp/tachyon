/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.runtime.AttributeKey;
import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.runtime.InteractionContext.Lifecycle;
import dev.tachyonmcp.api.server.domain.RequestId;
import dev.tachyonmcp.core.protocol.Protocol;
import dev.tachyonmcp.core.protocol.ProtocolRequestMapper;
import dev.tachyonmcp.core.protocol.ProtocolResponseMapper;
import dev.tachyonmcp.core.server.McpDispatcher;
import dev.tachyonmcp.core.server.RpcMethodHandler;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.server.session.DispatchContext;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class DefaultChannelContextTest {

    @Test
    void shouldCreateContextWithProtocol() {
        var ctx = new DefaultChannelContext(new FakeProtocol());
        InteractionContext handlerContext = ctx;

        assertThat(ctx.protocol()).isNotNull();
        assertThat(handlerContext.protocolVersion()).isEqualTo("1.0");
        assertThat(handlerContext.sessionId()).isNull();
        assertThat(ctx.lifecycle()).isEqualTo(Lifecycle.INITIALIZATION);
        assertThat(ctx.session()).isNull();
    }

    @Test
    void shouldTrackLifecycle() {
        var ctx = new DefaultChannelContext(new FakeProtocol());

        ctx.setLifecycle(Lifecycle.OPERATION);
        assertThat(ctx.lifecycle()).isEqualTo(Lifecycle.OPERATION);
    }

    @Test
    void shouldExposeSessionIdWithoutExposingSession() {
        var ctx = new DefaultChannelContext(new FakeProtocol());
        InteractionContext handlerContext = ctx;

        ctx.setSession(new Session("sess-1", SseConnection.noop()));

        assertThat(handlerContext.sessionId()).isEqualTo("sess-1");
    }

    @Test
    void shouldStoreAndRetrieveAttributesByTypedKey() throws Exception {
        var key = AttributeKey.<String>of("greeting");
        var before = new AtomicReference<@Nullable Optional<String>>();
        var after = new AtomicReference<@Nullable Optional<String>>();

        try (TachyonServer server = TachyonServer.builder().build()) {
            var engine = (ServerEngine) server;
            engine.registerHandler("test/attributes", new RpcMethodHandler<>() {
                @Override
                public String method() {
                    return "test/attributes";
                }

                @Override
                public Object decode(DispatchContext context, Object rawParams) {
                    return rawParams;
                }

                @Override
                public Object handle(DispatchContext context, Object params) {
                    before.set(context.get(key));
                    context.set(key, "hello");
                    after.set(context.get(key));
                    return Map.of();
                }
            });

            new McpDispatcher(engine, Runnable::run)
                    .dispatchRequestAsync(RequestId.of(1), "test/attributes", Map.of(), null)
                    .get(5, TimeUnit.SECONDS);
        }

        assertThat(before.get()).isEmpty();
        assertThat(after.get()).contains("hello");
    }

    @Test
    void shouldNotCollideBetweenDistinctKeysWithTheSameDebugName() throws Exception {
        var keyA = AttributeKey.<String>of("shared-name");
        var keyB = AttributeKey.<String>of("shared-name");
        var values = new AtomicReference<@Nullable Map<AttributeKey<String>, Optional<String>>>();

        try (TachyonServer server = TachyonServer.builder().build()) {
            var engine = (ServerEngine) server;
            engine.registerHandler("test/attributes", new RpcMethodHandler<>() {
                @Override
                public String method() {
                    return "test/attributes";
                }

                @Override
                public Object decode(DispatchContext context, Object rawParams) {
                    return rawParams;
                }

                @Override
                public Object handle(DispatchContext context, Object params) {
                    context.set(keyA, "from-a");
                    context.set(keyB, "from-b");
                    values.set(Map.of(keyA, context.get(keyA), keyB, context.get(keyB)));
                    return Map.of();
                }
            });

            new McpDispatcher(engine, Runnable::run)
                    .dispatchRequestAsync(RequestId.of(1), "test/attributes", Map.of(), null)
                    .get(5, TimeUnit.SECONDS);
        }

        assertThat(values.get()).containsEntry(keyA, Optional.of("from-a"));
        assertThat(values.get()).containsEntry(keyB, Optional.of("from-b"));
    }

    private static final class FakeProtocol implements Protocol {

        @Override
        public String familyName() {
            return "test";
        }

        @Override
        public String versionString() {
            return "1.0";
        }

        @Override
        public boolean matches(io.netty.handler.codec.http.HttpRequest request) {
            return false;
        }

        @Override
        public ProtocolResponseMapper responseMapper() {
            return null;
        }

        @Override
        public ProtocolRequestMapper requestMapper() {
            return new dev.tachyonmcp.core.protocol.mcp.v2025_11_25.codecs.McpRequestMapper();
        }

        @Override
        public ChannelContext createInteractionContext() {
            return new DefaultChannelContext(this);
        }
    }
}
