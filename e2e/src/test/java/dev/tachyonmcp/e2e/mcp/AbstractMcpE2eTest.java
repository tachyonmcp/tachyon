/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp;

import dev.tachyonmcp.core.server.ServerBuilder;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.server.config.SessionConfig;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.testkit.Mcp20260728Client;
import dev.tachyonmcp.testkit.McpClient;
import dev.tachyonmcp.testkit.McpTestClients;
import dev.tachyonmcp.testkit.McpTestServers;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractMcpE2eTest<C extends McpClient> {

    protected TachyonServer server;
    protected int port;
    protected boolean usingCustomServer;

    protected enum SessionMode {
        STATEFUL,
        STATELESS
    }

    /** Each subclass declares its session policy once. */
    protected abstract SessionMode sessionMode();

    /** Exposes the internal engine SPI for tests that need server introspection. */
    protected ServerEngine engine() {
        return (ServerEngine) server;
    }

    @BeforeAll
    void beforeAll() {
        startDefaultServer();
    }

    @AfterAll
    void tearDown() {
        closeCustomServerIfRunning();
    }

    protected abstract C createTestClient();

    protected abstract C createTestClient(int port);

    protected McpClient createTestClient(String protocolVersion) {
        return McpTestClients.forVersion(port, protocolVersion);
    }

    protected Mcp20260728Client createModernTestClient() {
        return McpTestClients.latest(port);
    }

    // region: ---- McpServer lifecycle management (call from subclass setup / teardown) ----

    /** Starts the session-mode-appropriate shared singleton server. */
    protected void startDefaultServer() {
        var h = SharedE2eServer.ensureStarted();
        this.server = h;
        this.port = h.port();
        this.usingCustomServer = false;
    }

    protected void startEmptyServer() {
        startServer(b -> {});
    }

    /**
     * Builds and starts a fresh server. The test consumer is applied FIRST, then the parent's
     * {@link #sessionMode()} is enforced — tests cannot accidentally override session policy.
     */
    protected final void startServer(Consumer<ServerBuilder> configurer) {
        startServer(configurer, server -> {});
    }

    protected final void startServerWith(Consumer<TachyonServer> registrar) {
        startServer(builder -> {}, registrar);
    }

    protected final void startServer(Consumer<ServerBuilder> configurer, Consumer<TachyonServer> registrar) {
        closeCustomServerIfRunning();
        var builder = TachyonServer.builder().port(0);
        configurer.accept(builder);
        if (sessionMode() == SessionMode.STATEFUL) {
            builder.session(SessionConfig.Builder::enabled);
        }
        var started = McpTestServers.startSafely(builder, registrar);
        this.server = started;
        this.port = started.port();
        this.usingCustomServer = true;
    }

    private void closeCustomServerIfRunning() {
        if (usingCustomServer) {
            server.close();
            server = null;
            usingCustomServer = false;
        }
    }

    protected void stopServer() {
        if (usingCustomServer) {
            server.close();
            server = null;
            usingCustomServer = false;
        }
    }

    // endregion
}
