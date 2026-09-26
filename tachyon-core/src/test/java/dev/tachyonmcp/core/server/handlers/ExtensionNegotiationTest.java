/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.handlers;

import static dev.tachyonmcp.core.test.TestUtils.decodeAndHandle;
import static dev.tachyonmcp.core.test.TestUtils.newEngine;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.extensions.AdvertiseMode;
import dev.tachyonmcp.api.server.extensions.ExtensionSettings;
import dev.tachyonmcp.api.server.extensions.ServerExtension;
import dev.tachyonmcp.core.protocol.Protocols;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ClientCapabilities;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.InitializeRequestParams;
import dev.tachyonmcp.core.runtime.Session;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.server.json.JsonUtils;
import dev.tachyonmcp.core.server.session.DefaultDispatchContext;
import dev.tachyonmcp.core.server.session.DispatchContext;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

class ExtensionNegotiationTest {

    private ServerEngine server;
    private Session session;
    private TestExtension testExtension;

    @BeforeEach
    void setUp() {
        testExtension = new TestExtension();
        server = newEngine(b -> b.withExtensions(testExtension));
        session = server.createSession("sess_ext_neg");
    }

    @Test
    void extensionEnabledWhenBothSidesDeclare() throws Exception {
        var handler = server.getHandler("initialize");
        var params = buildInitParams(Map.of("com.test/ext1", JsonNodeFactory.instance.objectNode()));
        var context = context(session, server);
        decodeAndHandle(handler, context, params);

        assertThat(context.isExtensionEnabled("com.test/ext1")).isTrue();
    }

    @Test
    void extensionNotEnabledWhenClientDoesNotDeclare() throws Exception {
        var handler = server.getHandler("initialize");
        var params = buildInitParams(Map.of());
        var context = context(session, server);
        decodeAndHandle(handler, context, params);

        assertThat(context.isExtensionEnabled("com.test/ext1")).isFalse();
    }

    @Test
    void onConnectionInitCalledForNegotiatedExtension() throws Exception {
        var handler = server.getHandler("initialize");
        var params = buildInitParams(
                Map.of("com.test/ext1", JsonNodeFactory.instance.objectNode().put("client", "test")));
        var context = context(session, server);
        decodeAndHandle(handler, context, params);

        assertThat(testExtension.initCalled.get()).isTrue();
        assertThat(testExtension.clientSettings.values().stringValue("client")).isEqualTo("test");
    }

    @Test
    void onConnectionInitNotCalledForUnnegotiatedExtension() throws Exception {
        var handler = server.getHandler("initialize");
        var params = buildInitParams(Map.of());
        var context = context(session, server);
        decodeAndHandle(handler, context, params);

        assertThat(testExtension.initCalled.get()).isFalse();
    }

    private static DispatchContext context(Session session, ServerEngine server) {
        var ctx = DefaultDispatchContext.create(Protocols.list().get(0), server);
        ctx.setSession(session);
        return ctx;
    }

    private static InitializeRequestParams buildInitParams(Map<String, JsonNode> extensions) {
        var extensionsNode = JsonUtils.toObjectTree(extensions);
        var capabilities =
                ClientCapabilities.builder().extensions(extensionsNode).build();
        return InitializeRequestParams.builder()
                .protocolVersion("2025-11-25")
                .capabilities(capabilities)
                .build();
    }

    @Test
    void sessionBackedExtensionStateIsSharedAcrossContexts() {
        var ctxA = context(session, server);
        var ctxB = context(session, server);

        ctxA.enableExtension("test-ext");
        assertThat(ctxA.isExtensionEnabled("test-ext")).isTrue();
        assertThat(ctxB.isExtensionEnabled("test-ext")).isTrue();
    }

    private static class TestExtension implements ServerExtension {

        final AtomicBoolean initCalled = new AtomicBoolean();
        ExtensionSettings clientSettings = ExtensionSettings.empty();

        @Override
        public String extensionId() {
            return "com.test/ext1";
        }

        @Override
        public AdvertiseMode advertiseMode() {
            return AdvertiseMode.ALWAYS;
        }

        @Override
        public void onConnectionInit(InteractionContext context, ExtensionSettings clientSettings) {
            initCalled.set(true);
            this.clientSettings = clientSettings;
        }
    }
}
