/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty;

import static dev.tachyonmcp.core.test.TestUtils.decodeAndHandle;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.server.domain.RequestId;
import dev.tachyonmcp.api.server.extensions.AdvertiseMode;
import dev.tachyonmcp.api.server.extensions.ExtensionContext;
import dev.tachyonmcp.api.server.extensions.ExtensionNegotiation;
import dev.tachyonmcp.api.server.extensions.ServerExtension;
import dev.tachyonmcp.core.protocol.Protocols;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ClientCapabilities;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.InitializeRequestParams;
import dev.tachyonmcp.core.runtime.Session;
import dev.tachyonmcp.core.server.McpDispatcher;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.server.session.DefaultDispatchContext;
import dev.tachyonmcp.core.server.session.DispatchContext;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

class ExtensionMethodRoutingTest {

    private ServerEngine server;
    private Session session;
    private McpDispatcher dispatcher;
    private @Nullable DispatchContext context;

    @BeforeEach
    void setUp() {
        server = (ServerEngine)
                TachyonServer.builder().withExtensions(new TestExtension()).build();
        session = server.createSession("sess_routing");
        dispatcher = new McpDispatcher(server, server.executor());
    }

    @Test
    void rejectsExtensionMethodWhenNotNegotiated() {
        session.activate();
        var ctx = DefaultDispatchContext.create(Protocols.list().get(0), server);
        ctx.setSession(session);
        var result = (McpDispatcher.DispatchResult.Response) dispatcher
                .dispatchRequestAsync(RequestId.of(1), "test/ext-method", null, "sess_routing", null, ctx)
                .join();
        var body = result.responseBodyString();
        assertThat(body).contains("error");
        assertThat(body).contains("-32003").contains("\"requiredCapabilities\"");
    }

    @Test
    void dispatchesExtensionMethodWhenNegotiated() throws Exception {
        negotiateExtension();
        session.activate();
        var params = Map.<String, Object>of();
        var result = (McpDispatcher.DispatchResult.Response) dispatcher
                .dispatchRequestAsync(RequestId.of(1), "test/ext-method", params, "sess_routing", null, context)
                .join();
        var body = result.responseBodyString();
        assertThat(body).contains("result");
    }

    @Test
    void tasksMethodsAreNotGated() {
        assertThat(server.extensionForMethod("tasks/get")).isNull();
        assertThat(server.extensionForMethod("tasks/list")).isNull();
        assertThat(server.extensionForMethod("tasks/result")).isNull();
    }

    private void negotiateExtension() throws Exception {
        var handler = server.getHandler("initialize");
        var caps = ClientCapabilities.builder()
                .extensions(Map.of("com.test/ext", JsonNodeFactory.instance.objectNode()))
                .build();
        var params = InitializeRequestParams.builder()
                .protocolVersion("2025-11-25")
                .capabilities(caps)
                .build();
        var ctx = DefaultDispatchContext.create(Protocols.list().get(0), server);
        ctx.setSession(session);
        decodeAndHandle(handler, ctx, params);
        this.context = ctx;
    }

    private static class TestExtension implements ServerExtension {

        @Override
        public String extensionId() {
            return "com.test/ext";
        }

        @Override
        public AdvertiseMode advertiseMode() {
            return AdvertiseMode.ALWAYS;
        }

        @Override
        public Set<String> methods() {
            return Set.of("test/ext-method");
        }

        @Override
        public ExtensionNegotiation negotiation() {
            return ExtensionNegotiation.REQUIRED;
        }

        @Override
        public void bootstrap(ExtensionContext context) {
            context.registerHandler("test/ext-method", (interaction, params) -> Map.of("status", "ok"));
        }
    }
}
