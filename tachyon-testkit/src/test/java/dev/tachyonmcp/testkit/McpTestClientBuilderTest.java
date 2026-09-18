/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.testkit;

import static dev.tachyonmcp.testkit.McpHttpResponseAssert.assertThatResponse;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tachyonmcp.core.server.TachyonServer;
import java.net.URI;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Verifies {@link McpTestClientBuilder} against a real port-0 server. */
class McpTestClientBuilderTest {

    private static TachyonServer server;
    private static int port;

    @BeforeAll
    static void startServer() {
        server = McpTestServers.start(b -> b.session(c -> c.enabled()), s -> {});
        port = server.port();
    }

    @AfterAll
    static void stopServer() {
        server.close();
    }

    @Test
    void buildReturnsAnAlreadyInitializedClient() throws Exception {
        try (var client =
                McpTestClients.builder(port).protocolVersion("2025-11-25").build()) {
            var ping = client.sendRpc("""
                    {"jsonrpc":"2.0","id":1,"method":"ping"}
                    """);

            assertThatResponse(ping).isSuccess();
        }
    }

    @Test
    void buildAgainstAnExplicitEndpointReturnsAnInitializedClient() throws Exception {
        var endpoint = URI.create("http://localhost:" + port + "/mcp");

        try (var client =
                McpTestClients.builder(endpoint).protocolVersion("2025-11-25").build()) {
            var ping = client.sendRpc("""
                    {"jsonrpc":"2.0","id":1,"method":"ping"}
                    """);

            assertThatResponse(ping).isSuccess();
        }
    }

    @Test
    void buildPropagatesUnsupportedOperationForAProtocolVersionWithoutInitialize() {
        var builder = McpTestClients.builder(port).protocolVersion("2026-07-28");

        assertThatThrownBy(builder::build).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void buildFailsFastWhenProtocolVersionWasNeverSet() {
        var builder = McpTestClients.builder(port);

        assertThatThrownBy(builder::build).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void buildRejectsAnUnsupportedProtocolVersion() {
        var builder = McpTestClients.builder(port).protocolVersion("2024-11-05");

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2024-11-05");
    }
}
