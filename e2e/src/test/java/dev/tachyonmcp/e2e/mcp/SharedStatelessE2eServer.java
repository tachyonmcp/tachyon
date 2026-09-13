/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp;

import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.testkit.McpTestServers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class SharedStatelessE2eServer {

    private SharedStatelessE2eServer() {}

    static TachyonServer ensureStarted() {
        return Holder.SERVER;
    }

    /** Class-initialization holder: the JVM guarantees a single lazy start, no locking in user code. */
    private static final class Holder {

        private static final Logger logger = LoggerFactory.getLogger(SharedStatelessE2eServer.class);
        private static final TachyonServer SERVER = start();

        private static TachyonServer start() {
            var server = McpTestServers.startSafely(
                    TachyonServer.builder()
                            .capabilities(c -> c.tools().logging())
                            .network(n -> n.port(0)),
                    s -> s.tools().registerAsync(EchoToolHandler.DESCRIPTOR, EchoToolHandler.FN));
            Runtime.getRuntime().addShutdownHook(new Thread(server::close, "shared-stateless-e2e-server-close"));
            logger.info("Shared stateless E2E server started on port {}", server.port());
            return server;
        }
    }
}
