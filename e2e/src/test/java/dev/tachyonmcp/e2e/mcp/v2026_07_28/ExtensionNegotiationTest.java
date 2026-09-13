/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2026_07_28;

import static dev.tachyonmcp.testkit.JsonRpcResponseAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.extensions.AdvertiseMode;
import dev.tachyonmcp.api.server.extensions.ExtensionSettings;
import dev.tachyonmcp.api.server.extensions.ServerExtension;
import dev.tachyonmcp.e2e.mcp.AbstractStatelessMcpE2eTest;
import dev.tachyonmcp.testkit.Mcp20260728Client;
import dev.tachyonmcp.testkit.McpClient;
import dev.tachyonmcp.testkit.McpTestClients;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * 2026-07-28 has no {@code initialize} handshake, so extension negotiation happens per request via
 * {@code ExtensionNegotiationHandler} rather than {@code InitializeHandler}. Both now share
 * {@code ExtensionNegotiator}, so a negotiated extension's {@link ServerExtension#onConnectionInit}
 * fires under 2026-07-28 too, not just 2025-11-25.
 */
class ExtensionNegotiationTest extends AbstractStatelessMcpE2eTest<McpClient> {

    @Override
    protected Mcp20260728Client createTestClient() {
        return createTestClient(port);
    }

    @Override
    protected Mcp20260728Client createTestClient(int port) {
        return McpTestClients.latest(port);
    }

    private static final String EXT_ID = "com.example/onconnectinit-test";

    @Test
    void onConnectionInitFiresForPerRequestDeclaration() throws Exception {
        var extension = new RecordingExtension();
        startServer(builder -> builder.withExtensions(extension), registrar -> {});

        try (var client = createModernTestClient()) {
            var response = client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{\
                    "_meta":{"io.modelcontextprotocol/clientCapabilities":\
                    {"extensions":{"%s":{"client":"test"}}}}}}
                    """.formatted(EXT_ID));

            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        }

        assertThat(extension.initCalled.get()).isTrue();
        assertThat(extension.clientSettings.values().stringValue("client")).isEqualTo("test");
    }

    @Test
    void onConnectionInitNotCalledWhenNotDeclared() throws Exception {
        var extension = new RecordingExtension();
        startServer(builder -> builder.withExtensions(extension), registrar -> {});

        try (var client = createModernTestClient()) {
            var response = client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"tools/list"}
                    """);

            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        }

        assertThat(extension.initCalled.get()).isFalse();
    }

    @Test
    void nonObjectParamsAreRejectedWithoutNegotiation() throws Exception {
        var extension = new RecordingExtension();
        startServer(builder -> builder.withExtensions(extension), registrar -> {});

        var response = postMcpRequest("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list","params":[\
                {"_meta":{"io.modelcontextprotocol/clientCapabilities":\
                {"extensions":{"%s":{"client":"test"}}}}}]}
                """.formatted(EXT_ID), Map.of("Mcp-Method", "tools/list"));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(response).isJsonRpcError().hasId(1).hasErrorCode(-32602);
        assertThat(extension.initCalled.get()).isFalse();
    }

    private static final class RecordingExtension implements ServerExtension {

        final AtomicBoolean initCalled = new AtomicBoolean();
        volatile ExtensionSettings clientSettings = ExtensionSettings.empty();

        @Override
        public String extensionId() {
            return EXT_ID;
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
